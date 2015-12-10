package org.ggp.base.player.gamer.statemachine.learner;

import gnu.trove.map.hash.TObjectDoubleHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;

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

  private final int mSize;
  private final NeuralNetwork<BackPropagation> mNetwork;
  BackPropagation mLearningRule;

  private final DataSet mTrainingSet;
  TObjectDoubleHashMap<ForwardDeadReckonInternalMachineState> mTrainingData = new TObjectDoubleHashMap<>();
  TObjectIntHashMap<ForwardDeadReckonInternalMachineState> mTrainingCount = new TObjectIntHashMap<>();

  public TrainedEvaluationFunction(int xiSize)
  {
    LOGGER.info("Creating an evaluation function with " + xiSize + " inputs");

    // Create a neural network.
    //
    mSize = xiSize;
    mNetwork = new MultiLayerPerceptron(TransferFunctionType.SIGMOID,
                                        mSize,          // Input layer, 1 neuron per base proposition
                                        mSize * 2,      // Hidden layer(s)
                                        mSize / 2,
                                        1);             // Output layer, 1 neuron
    double lRange = 1 / Math.sqrt(xiSize);
    mNetwork.randomizeWeights(-lRange, lRange);

    // Create a training set.
    mTrainingSet = new DataSet(mSize, 1);

    // Create a learning rule for a single update.
    mLearningRule = new BackPropagation();
    // mLearningRule.setErrorFunction(new MeanCubedError());
    mLearningRule.setMaxIterations(1);
    mLearningRule.setLearningRate(0.05);
    mLearningRule.setNeuralNetwork(mNetwork);
  }

  /**
   * Evaluate a state according to the evaluation function.
   *
   * @param xiState - the state to evaluate.
   *
   * @return an estimate of the value of the state.
   */
  public double evaluate(ForwardDeadReckonInternalMachineState xiState)
  {
    double[] lInputs = convertStateToInputs(xiState);
    mNetwork.setInput(lInputs);
    mNetwork.calculate();
    return mNetwork.getOutput()[0] * 100;
  }

  /**
   * Set a training example for the evaluation function, mapping a state to its value.
   *
   * @param xiState - the state.
   * @param xiValue - the value to learn.
   */
  public void sample(ForwardDeadReckonInternalMachineState xiState, double xiValue)
  {
    // Copy the state.  The one we've been passed will be reused.
    ForwardDeadReckonInternalMachineState lState = new ForwardDeadReckonInternalMachineState(xiState);

    // Storing the training example.  Later samples (for the same state) override earlier ones.
    mTrainingData.put(lState, xiValue);

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
    double[] lInputs = new double[mSize];
    OpenBitSet lState = xiState.getContents();
    for (int lii = 0; lii < mSize; lii++)
    {
      lInputs[lii] = lState.fastGet(lii + xiState.firstBasePropIndex) ? 1 : -1;
    }
    return lInputs;
  }

  public void train()
  {
    // Convert the training data into the form required by Neuroph.
    mTrainingSet.clear();
    for (Object lStateObj : mTrainingData.keys())
    {
      ForwardDeadReckonInternalMachineState lState = (ForwardDeadReckonInternalMachineState)lStateObj;
      double[] lInputs = convertStateToInputs(lState);

      // Add additional weight to states that have been seen often.
      for (int lii = 0; lii < mTrainingCount.get(lState); lii++)
      {
        mTrainingSet.addRow(lInputs, new double[] {mTrainingData.get(lState) / 100});
      }
    }

    // Train the network.
    // LOGGER.info("Training with " + mTrainingSet.size() + " samples");
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