package org.ggp.base.player.gamer.statemachine.mctsref;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.player.gamer.statemachine.sancho.ThreadControl;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MctsRefGamer extends StateMachineGamer
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final long                     SAFETY_MARGIN = 2500;

  private ForwardDeadReckonPropnetStateMachine  mUnderlyingStateMachine = null;
  private SearchTree                            mTree = null;
  private int                                   mTurnCount = 0;

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
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    mUnderlyingStateMachine.enableGreedyRollouts(false, true);
    mTree = new BasicMCTSSearchTree(mUnderlyingStateMachine);
    mTurnCount = 0;
  }

  @Override
  public Move stateMachineSelectMove(long xiTimeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    long finishBy = xiTimeout - SAFETY_MARGIN;
    int iterations = 0;
    boolean lFirstDump = true;

    System.out.println("Starting turn " + ++mTurnCount);

    //  Convert to internal rep
    ForwardDeadReckonInternalMachineState currentState = mUnderlyingStateMachine.createInternalState(getCurrentState());

    //  For now reset the tree every turn
    mTree.clear(currentState);

    while(System.currentTimeMillis() < finishBy && !mTree.isSolved())
    {
      iterations++;
      mTree.grow();

      // Dump data to allow us to plot learning curves.
      if ((iterations % 5000) == 0)
      {
        mTree.dumpRootData(lFirstDump);
        lFirstDump = false;
      }
    }

    Move bestMove = mTree.getBestMove();
    System.out.println("Processed " + iterations + " iterations, and playing: " + bestMove);
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
  public void preview(Game xiG, long xiTimeout) throws GamePreviewException
  {
    // TODO Auto-generated method stub

  }

  @Override
  public String getName()
  {
    return "MCTSRefPlayer";
  }
}
