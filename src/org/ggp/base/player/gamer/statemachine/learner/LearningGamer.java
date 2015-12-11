package org.ggp.base.player.gamer.statemachine.learner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.player.gamer.statemachine.sancho.ThreadControl;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class LearningGamer extends StateMachineGamer
{
  private static final Logger LOGGER = LogManager.getLogger();
  private static final long SAFETY_MARGIN = 2500;

  private TrainedEvaluationFunction             mEvalFunc;
  private TrainedEvaluationFunction             mFrozenEvalFunc;
  private ForwardDeadReckonPropnetStateMachine  mUnderlyingStateMachine = null;
  private int                                   mTurn = 0;

  @Override
  public StateMachine getInitialStateMachine()
  {
    mUnderlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                                                                      getMetaGamingTimeout(),
                                                                      getRole(),
                                                                      mGameCharacteristics);

    System.gc();

    return mUnderlyingStateMachine;
  }

  @Override
  public void stateMachineMetaGame(long xiTimeout)
  {
    mUnderlyingStateMachine.enableGreedyRollouts(false, true);
    mTurn = 0;

    // Create and initialise a heuristic evaluation function.
    boolean lReload = false;
    boolean l2PlayerFixedSum = true;
    if (lReload)
    {
      String lFilename = "data/games/base.tictactoe/evaluation.0.5_14.nnet";
      mEvalFunc = new TrainedEvaluationFunction(lFilename, l2PlayerFixedSum);
      mFrozenEvalFunc = new TrainedEvaluationFunction(lFilename, l2PlayerFixedSum);
    }
    else
    {
      mEvalFunc = new TrainedEvaluationFunction(mUnderlyingStateMachine.getBasePropositions().size(),
                                                mUnderlyingStateMachine.getRoles().length,
                                                l2PlayerFixedSum);
      mFrozenEvalFunc = new TrainedEvaluationFunction(mUnderlyingStateMachine.getBasePropositions().size(),
                                                      mUnderlyingStateMachine.getRoles().length,
                                                      l2PlayerFixedSum);
    }

    // Use TreeStrap to train the evaluation function.
    treeStrap(xiTimeout);
  }

  private void treeStrap(long xiTimeout)
  {
    double lEpsilon = 0.2;
    int lIterations = 0;

    // Create a reference tree for test purposes.
    LearningTree lReferenceTree = new LearningTree(mUnderlyingStateMachine, mEvalFunc, mFrozenEvalFunc);
    lReferenceTree.search(mUnderlyingStateMachine.createInternalState(mUnderlyingStateMachine.getInitialState()), 9);
    int lFewestWrongMoves = 9999;
    double lSmallestStateError = 100;

    while (true /* System.currentTimeMillis() < xiTimeout */)
    {
      // Do sample playouts through the tree, from the root state, learning as we go.
      ForwardDeadReckonInternalMachineState lState =
                                 mUnderlyingStateMachine.createInternalState(mUnderlyingStateMachine.getInitialState());

      // Every so often, update the frozen (target) evaluation function from the training one.  This is necessary for
      // stability.
      if (lIterations % 100 == 0)
      {
        mFrozenEvalFunc.replaceWith(mEvalFunc);
        mEvalFunc.save();
        mEvalFunc.cool();
        lEpsilon *= 1.01;
      }

      if (lIterations % 100 == 0)
      {
        int lWrongMoves = lReferenceTree.getWrongMoves(false);
        double lAverageError = lReferenceTree.getAverageError();

        if ((lWrongMoves < lFewestWrongMoves) ||
            ((lWrongMoves == lFewestWrongMoves) && (lAverageError < lSmallestStateError)))
        {
          lFewestWrongMoves = lWrongMoves;
          lSmallestStateError = lAverageError;

          if (lWrongMoves < 10)
          {
            // Dump the states in which we get wrong moves.
            LOGGER.info("--- Dumping " + lWrongMoves + " bad states");
            lReferenceTree.getWrongMoves(true);
          }
        }

        LOGGER.info("After " + lIterations + " iterations, average error = " + lAverageError + ", wrong moves = " + lWrongMoves + ", low-water mark = " + lFewestWrongMoves);

        showSampleGame();
      }

      // Clear the training set.
      mEvalFunc.clearSamples();

      // Do a rollout with n-ply lookahead at each step.
      while (!mUnderlyingStateMachine.isTerminal(lState))
      {
        // Build a depth-limited game tree, by minimax, using the position evaluation function at the non-terminal
        // leaf nodes.  Builds the training set in the process.
        LearningTree lTree = new LearningTree(mUnderlyingStateMachine, mEvalFunc, mFrozenEvalFunc);
        lTree.search(lState, 9);

        // Pick the next move epsilon-greedily.
        lState = lTree.epsilonGreedySelection(lState, lEpsilon, false);
      }

      // Train the evaluation function on all the samples gathered during the rollout.
      mEvalFunc.train();

      lIterations++;
    }
  }

  private void showSampleGame()
  {
    LOGGER.info("--- Sample game");

    LearningTree lTree = new LearningTree(mUnderlyingStateMachine, mEvalFunc, mFrozenEvalFunc);
    ForwardDeadReckonInternalMachineState lState =
                                 mUnderlyingStateMachine.createInternalState(mUnderlyingStateMachine.getInitialState());
    while (!mUnderlyingStateMachine.isTerminal(lState))
    {
      // Pick the next move greedily.
      lState = lTree.epsilonGreedySelection(lState, 0, true);
    }
  }

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
  {
    mTurn++;
    LOGGER.info("> > > > > Starting turn " + mTurn + " < < < < <");

    //  Convert to internal rep
    ForwardDeadReckonInternalMachineState currentState = mUnderlyingStateMachine.createInternalState(getCurrentState());

    // Read off the best move according to the learned weights.
    // !! ARR ...

    Move bestMove = null;
    System.out.println("Playing: " + bestMove);
    return bestMove;
  }

  @Override
  public void stateMachineStop()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void stateMachineAbort()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void preview(Game xiGame, long xiTimeout) throws GamePreviewException
  {
    // TODO Auto-generated method stub

  }

  @Override
  public String getName()
  {
    return "Learner";
  }
}
