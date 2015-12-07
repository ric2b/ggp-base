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
    mEvalFunc = new TrainedEvaluationFunction(mUnderlyingStateMachine.getBasePropositions().size());

    // Use TreeStrap to train the evaluation function.
    treeStrap(xiTimeout);
  }

  private void treeStrap(long xiTimeout)
  {
    int lIterations = 0;

    // Create a reference tree for test purposes.
    LearningTree lReferenceTree = new LearningTree(mUnderlyingStateMachine, mEvalFunc, getRole());
    lReferenceTree.search(mUnderlyingStateMachine.createInternalState(mUnderlyingStateMachine.getInitialState()), 9);
    int lFewestWrongMoves = 9999;

    while (true /* System.currentTimeMillis() < xiTimeout */)
    {
      // Do sample playouts through the tree, from the root state, learning as we go.
      ForwardDeadReckonInternalMachineState lState =
                                 mUnderlyingStateMachine.createInternalState(mUnderlyingStateMachine.getInitialState());

      int lWrongMoves = lReferenceTree.getWrongMoves();
      if (lWrongMoves < lFewestWrongMoves)
      {
        lFewestWrongMoves = lWrongMoves;
      }

      if (lIterations % 100 == 0)
      {
        LOGGER.info("After " + lIterations + " iterations, average error = " + lReferenceTree.getAverageError() + ", wrong moves = " + lWrongMoves + ", low-water mark = " + lFewestWrongMoves);
      }

      while (!mUnderlyingStateMachine.isTerminal(lState))
      {
        // Clear the training set.
        mEvalFunc.clearSamples();

        // Build a depth-limited game tree, by minimax, using the position evaluation function at the non-terminal
        // leaf nodes.  Builds the training set in the process.
        LearningTree lTree = new LearningTree(mUnderlyingStateMachine, mEvalFunc, getRole());
        lTree.search(lState, 2);

        // Train the evaluation function on the (partial) minimax tree.
        mEvalFunc.train();

        // Pick the next move greedily.
        lState = lTree.greedySelection(lState);
      }

      lIterations++;
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
