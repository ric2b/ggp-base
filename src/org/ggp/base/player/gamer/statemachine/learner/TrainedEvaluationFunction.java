package org.ggp.base.player.gamer.statemachine.learner;

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

  public TrainedEvaluationFunction(int xiSize)
  {
    LOGGER.info("Creating an evaluation function with " + xiSize + " inputs");

    // Create a neural network.
    //
    // - Input layer is the same size as the number of base propositions.
    // - Hidden layer is 2/3 of the input size.
    // - Output layer is a single node.
    mSize = xiSize;
    mNetwork = new MultiLayerPerceptron(TransferFunctionType.SIGMOID, mSize, mSize * 3 / 2, 1);
    double lRange = 1 / Math.sqrt(xiSize);
    mNetwork.randomizeWeights(-lRange, lRange);

    // Create a training set.
    mTrainingSet = new DataSet(mSize, 1);

    // Create a learning rule for a single update.
    mLearningRule = new BackPropagation();
    mLearningRule.setMaxIterations(1);
    mLearningRule.setMaxError(0);
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
    // Create an additional training row.
    double[] lInputs = convertStateToInputs(xiState);
    mTrainingSet.addRow(lInputs, new double[] {xiValue / 100});
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
    mLearningRule.doLearningEpoch(mTrainingSet);
  }
}