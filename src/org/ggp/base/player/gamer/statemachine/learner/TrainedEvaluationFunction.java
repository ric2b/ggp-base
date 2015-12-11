package org.ggp.base.player.gamer.statemachine.learner;

import gnu.trove.map.hash.TObjectIntHashMap;

import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.OpenBitSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.data.DataSet;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.BackPropagation;
import org.neuroph.util.TransferFunctionType;

/**
 * A generic position evaluation function, based on neural networks.
 */
public class TrainedEvaluationFunction
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final double INITIAL_LEARNING_RATE = 0.05;

  private final int mInputSize;
  private final int mOutputSize;
  private final boolean m2PlayerFixedSum;
  private final NeuralNetwork<BackPropagation> mNetwork;
  private final BackPropagation mLearningRule;

  private final DataSet mTrainingSet;
  HashMap<ForwardDeadReckonInternalMachineState, double[]> mTrainingData = new HashMap<>();
  TObjectIntHashMap<ForwardDeadReckonInternalMachineState> mTrainingCount = new TObjectIntHashMap<>();

  public TrainedEvaluationFunction(int xiInputSize, int xiOutputSize, boolean xi2PlayerFixedSum)
  {
    // Create a neural network.
    m2PlayerFixedSum = xi2PlayerFixedSum;
    mInputSize = xiInputSize;
    mOutputSize = xi2PlayerFixedSum ? 1 : xiOutputSize;

    mNetwork = new MultiLayerPerceptron(TransferFunctionType.SIGMOID,
                                        mInputSize,     // Input layer, 1 neuron per base proposition
                                        mInputSize * 2, // Hidden layer(s)
                                        mInputSize / 2,
                                        mOutputSize);   // Output layer, 1 neuron per role (except for fixed sum,
                                                        // where we only need 1).
    double lRange = 1 / Math.sqrt(mInputSize);
    mNetwork.randomizeWeights(-lRange, lRange);

    // Create a training set.
    mTrainingSet = new DataSet(mInputSize, mOutputSize);

    // Create a learning rule.
    mLearningRule = createLearningRule();

    LOGGER.info("Created an evaluation function with " + mNetwork.getInputsCount() + " inputs & " +
                mNetwork.getOutputsCount() + " outputs");

  }

  @SuppressWarnings("unchecked")
  public TrainedEvaluationFunction(String xiFilename, boolean xi2PlayerFixedSum)
  {
    // Load the neural network from disk.
    mNetwork = NeuralNetwork.createFromFile(xiFilename);
    mInputSize = mNetwork.getInputsCount();
    mOutputSize = mNetwork.getOutputsCount();
    m2PlayerFixedSum = xi2PlayerFixedSum;

    // Create a training set.
    mTrainingSet = new DataSet(mInputSize, mOutputSize);

    // Create a learning rule.
    mLearningRule = createLearningRule();
  }

  private BackPropagation createLearningRule()
  {
    BackPropagation lLearningRule = new BackPropagation();
    lLearningRule.setMaxIterations(1);
    lLearningRule.setLearningRate(INITIAL_LEARNING_RATE);
    lLearningRule.setNeuralNetwork(mNetwork);
    return lLearningRule;
  }

  /**
   * Evaluate a state according to the evaluation function.
   *
   * @param xiState - the state to evaluate.
   *
   * @return an estimate of the goal values in the specified state.
   */
  public double[] evaluate(ForwardDeadReckonInternalMachineState xiState)
  {
    double[] lInputs = convertStateToInputs(xiState);
    mNetwork.setInput(lInputs);
    mNetwork.calculate();

    double[] lOutputs;
    if (m2PlayerFixedSum)
    {
      lOutputs = new double[2];
      lOutputs[0] = mNetwork.getOutput()[0] * 100;
      lOutputs[1] = 100 - lOutputs[0];
    }
    else
    {
      lOutputs = mNetwork.getOutput().clone();
      for (int lii = 0; lii < mOutputSize; lii++)
      {
        lOutputs[lii] *= 100;
      }
    }
    return lOutputs;
  }

  /**
   * Set a training example for the evaluation function, mapping a state to its value.
   *
   * @param xiState      - the state.
   * @param xiGoalValues - the goal values to learn (in GDL order).
   */
  public void sample(ForwardDeadReckonInternalMachineState xiState,
                     double[] xiGoalValues)
  {
    assert((xiGoalValues.length == mOutputSize) || (m2PlayerFixedSum));

    // If this is a zero-sum game, only train from the first player's goal.  It's considerably more efficient than
    // trying to train both.
    if ((xiGoalValues.length == 2) && (m2PlayerFixedSum))
    {
      xiGoalValues = new double[] {xiGoalValues[0]};
    }

    // Copy the state.  The one we've been passed will be reused.
    ForwardDeadReckonInternalMachineState lState = new ForwardDeadReckonInternalMachineState(xiState);

    // Storing the training example.  Later samples (for the same state) override earlier ones.
    mTrainingData.put(lState, xiGoalValues);

    // Increment the count.
    if (mTrainingCount.containsKey(lState))
    {
      mTrainingCount.put(lState, mTrainingCount.get(lState) + 1);
    }
    else
    {
      mTrainingCount.put(lState, 1);
    }
  }

  public void clearSamples()
  {
    mTrainingData.clear();
    mTrainingCount.clear();
  }

  private double[] convertStateToInputs(ForwardDeadReckonInternalMachineState xiState)
  {
    double[] lInputs = new double[mInputSize];
    OpenBitSet lState = xiState.getContents();
    for (int lii = 0; lii < mInputSize; lii++)
    {
      lInputs[lii] = lState.fastGet(lii + xiState.firstBasePropIndex) ? 1 : -1;
    }
    return lInputs;
  }

  private double[] normaliseOutputs(double[] lGDLOutputs)
  {
    double[] lOutputs = new double[mOutputSize];
    for (int lii = 0; lii < mOutputSize; lii++)
    {
      lOutputs[lii] = lGDLOutputs[lii] / 100.0;
    }
    return lOutputs;
  }

  public void train()
  {
    // Convert the training data into the form required by Neuroph.
    mTrainingSet.clear();
    for (Entry<ForwardDeadReckonInternalMachineState, double[]> lEntry : mTrainingData.entrySet())
    {
      ForwardDeadReckonInternalMachineState lState = lEntry.getKey();

      // Add additional weight to states that have been seen often.
      for (int lii = 0; lii < mTrainingCount.get(lState); lii++)
      {
        mTrainingSet.addRow(convertStateToInputs(lState),
                            normaliseOutputs(lEntry.getValue()));
      }
    }

    // Train the network.
    mLearningRule.doOneLearningIteration(mTrainingSet);
  }

  public void replaceWith(TrainedEvaluationFunction xiSource)
  {
    mNetwork.setWeights(xiSource.mNetwork.getWeights());
  }

  public void cool()
  {
    mLearningRule.setLearningRate(mLearningRule.getLearningRate() * 0.9999);
  }

  public void save()
  {
    mNetwork.save("Game.nnet");
  }
}