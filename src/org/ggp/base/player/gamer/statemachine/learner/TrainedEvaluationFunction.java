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

  private static final double INITIAL_LEARNING_RATE = 0.01;

  private final int mInputSize;
  private final int mOutputSize;
  private final NeuralNetwork<BackPropagation> mNetwork;
  BackPropagation mLearningRule;

  private final DataSet mTrainingSet;
  HashMap<ForwardDeadReckonInternalMachineState, double[]> mTrainingData = new HashMap<>();
  TObjectIntHashMap<ForwardDeadReckonInternalMachineState> mTrainingCount = new TObjectIntHashMap<>();

  public TrainedEvaluationFunction(int xiInputSize, int xiOutputSize)
  {
    LOGGER.info("Creating an evaluation function with " + xiInputSize + " inputs");

    // Create a neural network.
    mInputSize = xiInputSize;
    mOutputSize = xiOutputSize;
    mNetwork = new MultiLayerPerceptron(TransferFunctionType.SIGMOID,
                                        mInputSize,     // Input layer, 1 neuron per base proposition
                                        mInputSize * 2, // Hidden layer(s)
                                        mInputSize / 2,
                                        xiOutputSize);  // Output layer, 1 neuron per role
    double lRange = 1 / Math.sqrt(mInputSize);
    mNetwork.randomizeWeights(-lRange, lRange);

    // Create a training set.
    mTrainingSet = new DataSet(mInputSize, mOutputSize);

    // Create a learning rule..
    mLearningRule = createLearningRule();
  }

  @SuppressWarnings("unchecked")
  public TrainedEvaluationFunction(String xiFilename)
  {
    // Load the neural network from disk.
    mNetwork = NeuralNetwork.createFromFile(xiFilename);
    mInputSize = mNetwork.getInputsCount();
    mOutputSize = mNetwork.getOutputsCount();

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

    double[] lOutputs = mNetwork.getOutput().clone();
    for (int lii = 0; lii < mOutputSize; lii++)
    {
      lOutputs[lii] *= 100;
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
    assert(xiGoalValues.length == mOutputSize);

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
    mLearningRule.setLearningRate(mLearningRule.getLearningRate() * 0.999);
  }

  public void save()
  {
    mNetwork.save("Game.nnet");
  }
}