package org.ggp.base.util.statemachine.playoutPolicy;

import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.StateMachineFilter;

/**
 * @author steve
 *  Playout policy implementing basic last good response
 */
public class PlayoutPolicyLastGoodResponse implements IPlayoutPolicy
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final double EPSILON_GREEDY_THRESHOLD = 0.7;

  private final ForwardDeadReckonPropnetStateMachine stateMachine;
  private final int[][]  bestResponseScores;
  private final int[] latchedScoreRangeBuffer = new int[2];

  private ForwardDeadReckonLegalMoveInfo[] playoutMoves = null;
  private int                              currentMoveIndex;
  private final int                        ourRoleIndex;
  private final Role                       ourRole;
  private int                              sampleSize = 0;
  final private Random                     rand = new Random();

  private ForwardDeadReckonLegalMoveSet    availableMoves = null;

  /**
   * Construct a new instance
   * @param xiStateMachine
   */
  public PlayoutPolicyLastGoodResponse(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
    bestResponseScores = new int[stateMachine.getFullPropNet().getMasterMoveList().length][stateMachine.getFullPropNet().getMasterMoveList().length];

    ourRoleIndex = stateMachine.getRoleOrdering().getOurRawRoleIndex();
    ourRole = stateMachine.getRoleOrdering().getOurRole();
  }

  @Override
  public IPlayoutPolicy cloneFor(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    return new PlayoutPolicyLastGoodResponse(stateMachine);
  }

  @Override
  public void noteCurrentState(ForwardDeadReckonInternalMachineState xiState,
                               ForwardDeadReckonLegalMoveSet xiLegalMoves,
                               StateMachineFilter xiFactor,
                               int xiMoveIndex,
                               ForwardDeadReckonLegalMoveInfo[] xiMoveHistory,
                               ForwardDeadReckonInternalMachineState[] xiStateHistory)
  {
    playoutMoves = xiMoveHistory;
    currentMoveIndex = xiMoveIndex;
    availableMoves = xiLegalMoves;

    if ( currentMoveIndex == 0 )
    {
      stateMachine.getLatchedScoreRange(xiState, ourRole, latchedScoreRangeBuffer);
    }
  }

  @Override
  public boolean requiresMoveHistory()
  {
    return true;
  }

  @Override
  public boolean requiresStateHistory()
  {
    return false;
  }

  @Override
  public boolean terminatePlayout()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void noteNewPlayout()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void noteCompletePlayout(int xiLength,
                                  ForwardDeadReckonLegalMoveInfo[] xiMoves,
                                  ForwardDeadReckonInternalMachineState[] xiStates)
  {
    if ( xiMoves != null )
    {
      ForwardDeadReckonLegalMoveInfo prev = null;
      int ourGoalValue = stateMachine.getGoal(ourRole);
      boolean isWin = (ourGoalValue == latchedScoreRangeBuffer[1]);

      for(int i = 0; i < xiLength; i++)
      {
        ForwardDeadReckonLegalMoveInfo move = xiMoves[i];
        if ( prev != null )
        {
          if ( (move.mRoleIndex == ourRoleIndex) == isWin )
          {
            bestResponseScores[prev.mMasterIndex][move.mMasterIndex]++;
          }
          else
          {
            bestResponseScores[prev.mMasterIndex][move.mMasterIndex]--;
          }
        }

        prev = move;
      }

      sampleSize++;
    }
  }

  @Override
  public ForwardDeadReckonLegalMoveInfo selectMove(int xiRoleIndex)
  {
    if ( currentMoveIndex == 0 || rand.nextDouble() > EPSILON_GREEDY_THRESHOLD )
    {
      return null;
    }

    ForwardDeadReckonLegalMoveInfo bestResponse = null;
    int[] moveResponseScores = bestResponseScores[playoutMoves[currentMoveIndex-1].mMasterIndex];
    int best = -Integer.MAX_VALUE;
    for(int i = 0; i < moveResponseScores.length; i++)
    {
      if ( moveResponseScores[i] > best )
      {
        best = moveResponseScores[i];
        ForwardDeadReckonLegalMoveInfo response = availableMoves.getMasterList()[i];
        if ( availableMoves.getContents(xiRoleIndex).contains(response) )
        {
          bestResponse = response;
          best = moveResponseScores[i];
        }
      }
    }

//    if ( bestResponse != null )
//    {
//      LOGGER.info("Selected move: " + bestResponse.inputProposition + " for role " + xiRoleIndex);
//    }
//    else
//    {
//      LOGGER.info("No selection made for role " + xiRoleIndex);
//    }
    return bestResponse;
  }

  @Override
  public boolean isAcceptableMove(ForwardDeadReckonLegalMoveInfo xiCandidate,
                                  int xiRoleIndex)
  {
    return true;
  }

  @Override
  public boolean isAcceptableState(ForwardDeadReckonInternalMachineState xiToState,
                                   int xiRoleIndex)
  {
    return true;
  }

  @Override
  public boolean popStackOnAllUnacceptableMoves(int xiPopDepth)
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void noteNewTurn()
  {
    //  Dump best responses from the previous turn
//    ForwardDeadReckonLegalMoveInfo[] masterList = stateMachine.getFullPropNet().getMasterMoveList();
//    for(int i = 0; i < bestResponseScores.length; i++)
//    {
//      int best = -Integer.MAX_VALUE;
//      int bestIndex = -1;
//      for(int j = 0; j < bestResponseScores.length; j++)
//      {
//        if ( bestResponseScores[i][j] > best )
//        {
//          best = bestResponseScores[i][j];
//          bestIndex = j;
//        }
//      }
//
//      assert(bestIndex != -1);
//      LOGGER.info("Best response to " + masterList[i] + ": " + masterList[bestIndex] + " (" + (100*(double)best/sampleSize) + "%)");
//    }

    // Reset the stats
    for(int i = 0; i < bestResponseScores.length; i++)
    {
      for(int j = 0; j < bestResponseScores.length; j++)
      {
        bestResponseScores[i][j] = 0;
      }
    }

    sampleSize = 0;
  }
}
