package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class LocalRegionSearcher implements Runnable
{
  private static final Logger LOGGER       = LogManager.getLogger();

  private final int MAX_BRANCHING_FACTOR = 100;
  private final int MAX_DEPTH            = 20;

  private final ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private final ForwardDeadReckonInternalMachineState startingState;
  private final RoleOrdering roleOrdering;
  private final ForwardDeadReckonLegalMoveInfo regionCentre;
  private final int optionalPlayRole;
  private final int numRoles;
  private volatile int                    searchSeqRequested  = 0;
  private volatile int                    searchSeqProcessing = 0;
  private volatile boolean                mTerminateRequested = false;

  private final ForwardDeadReckonLegalMoveInfo[] jointMove;
  private final ForwardDeadReckonLegalMoveInfo[][] chooserMoveChoiceStack;
  private final ForwardDeadReckonInternalMachineState[] childStateBuffer;
  private final ForwardDeadReckonLegalMoveInfo pseudoNoop;

  public LocalRegionSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    ForwardDeadReckonInternalMachineState xiStartingState,
                    RoleOrdering xiRoleOrdering,
                    ForwardDeadReckonLegalMoveInfo xiRegionCentre,
                    int xiOptionalPlayRole)
  {
    underlyingStateMachine = xiUnderlyingStateMachine;
    startingState = new ForwardDeadReckonInternalMachineState(xiStartingState);
    roleOrdering = xiRoleOrdering;
    regionCentre = xiRegionCentre;
    optionalPlayRole = xiOptionalPlayRole;

    numRoles = underlyingStateMachine.getRoles().length;
    jointMove = new ForwardDeadReckonLegalMoveInfo[numRoles];
    chooserMoveChoiceStack = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH][];
    childStateBuffer = new ForwardDeadReckonInternalMachineState[MAX_DEPTH];

    pseudoNoop = new ForwardDeadReckonLegalMoveInfo();
    pseudoNoop.isPseudoNoOp = true;
  }

  /**
   *
   */
  public void stop()
  {
    mTerminateRequested = true;
  }

  @Override
  public void run()
  {
    // Register this thread.
    ThreadControl.registerSearchThread();

    int depth = 0;

    do
    {
      LOGGER.info("Local move search beginning for depth " + depth);

      for(int i = 0; i < MAX_BRANCHING_FACTOR; i++)
      {
        chooserMoveChoiceStack[depth] = new ForwardDeadReckonLegalMoveInfo[MAX_BRANCHING_FACTOR];
      }
      childStateBuffer[depth] = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());

      ForwardDeadReckonInternalMachineState state = new ForwardDeadReckonInternalMachineState(startingState);

      int searchResult = searchToDepth(state, 0, depth);
      if ( searchResult == 0 )
      {
        LOGGER.info("Local search finds loss at depth " + depth);
        break;
      }
      if ( searchResult == 100 )
      {
        LOGGER.info("Local search finds win at depth " + depth);
        break;
      }
      depth++;
    } while (!mTerminateRequested && depth < MAX_DEPTH);

    LOGGER.info("Terminating LocalRegionSearcher");
  }

  /*
   * Search to specified depth, returning:
   *  0 if role 1 win
   *  100 if role 0 win
   *  else 50
   */
  private int searchToDepth(ForwardDeadReckonInternalMachineState state, int depth, int maxDepth)
  {
    if ( underlyingStateMachine.isTerminal(state))
    {
      int result = underlyingStateMachine.getGoal(state, roleOrdering.roleIndexToRole(0));
      //LOGGER.info("Terminal state: " + state);
      //LOGGER.info("Terminal state with our goal value: " + result + " (we are " + roleOrdering.roleIndexToRole(0) + ")");
      return result;
    }

    if ( depth == maxDepth || mTerminateRequested )
    {
      return 50;
    }

    int choosingRole = -1;
    int numChoices = 0;
    ForwardDeadReckonLegalMoveInfo nonChooserMove = null;

    for(int i = 0; i < numRoles; i++)
    {
      Role role = roleOrdering.roleIndexToRole(i);
      Collection<ForwardDeadReckonLegalMoveInfo> legalMoves = underlyingStateMachine.getLegalMoves(state, role);

      if ( legalMoves.iterator().next().inputProposition == null )
      {
        nonChooserMove = legalMoves.iterator().next();
      }
      else
      {
        numChoices = getLocalMoves(legalMoves, chooserMoveChoiceStack[depth], maxDepth);
        choosingRole = i;
      }
    }

    assert(choosingRole != -1);

    boolean incomplete = false;

    for(int i = 0; i < numChoices; i++)
    {
      jointMove[1-choosingRole] = nonChooserMove;
      jointMove[choosingRole] = chooserMoveChoiceStack[depth][i];

      underlyingStateMachine.getNextState(state, null, jointMove, childStateBuffer[depth]);

//      String moveDesc = "";
//      for(int j = 0; j < depth; j++)
//      {
//        moveDesc += " ";
//      }
//      moveDesc += "@" + depth + " choosing role " + choosingRole + ": " + jointMove[choosingRole].move;
//      LOGGER.info(moveDesc);
      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth);

      if ( childValue == (choosingRole == 0 ? 100 : 0) )
      {
        //  Complete result
//        LOGGER.info("Complete result: " + childValue + " (" + moveDesc + ")");
        return childValue;
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));
    }

//    if ( !incomplete )
//    {
//      LOGGER.info("Complete loss without Tenuki at depth " + depth);
//    }

    if ( choosingRole == optionalPlayRole )
    {
      //  Consider also a pseudo-noop
      jointMove[1-choosingRole] = nonChooserMove;
      jointMove[choosingRole] = pseudoNoop;

      underlyingStateMachine.getNextState(state, null, jointMove, childStateBuffer[depth]);

//      ForwardDeadReckonInternalMachineState diff = new ForwardDeadReckonInternalMachineState(childStateBuffer[depth]);
//      diff.xor(state);
//      LOGGER.info("NULL move parent->child state diff:" + diff);
//      String moveDesc = "";
//      for(int j = 0; j < depth; j++)
//      {
//        moveDesc += " ";
//      }
//      moveDesc += "@" + depth + " choosing role " + choosingRole + ": Tenuki";
//      LOGGER.info(moveDesc);
      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth);

      if ( childValue == (choosingRole == 0 ? 100 : 0) )
      {
        //  Complete result
        //LOGGER.info("Complete result: " + childValue + " (" + moveDesc + ")");
        return childValue;
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));
    }
    else
    {
      //  No moves available for non-optional role implies this branch completely
      //  searched with no win found
      return 50;
    }

    if ( !incomplete )
    {
      //LOGGER.info("@" + depth + " choosing role " + " - complete result due to all child completion");
      return (choosingRole == 0 ? 0 : 100);
    }

    return 50;
  }

  private int getLocalMoves(Collection<ForwardDeadReckonLegalMoveInfo> allMoves, ForwardDeadReckonLegalMoveInfo[] localMoves, int maxDistance)
  {
    int numChoices = 0;

    if ( moveDistances == null )
    {
      moveDistances = generateMoveDistanceMatrix();
    }

    for(ForwardDeadReckonLegalMoveInfo move : allMoves)
    {
      if ( regionCentre == null || moveDistances[move.masterIndex][regionCentre.masterIndex] <= maxDistance )
      {
        localMoves[numChoices++] = move;
      }
    }

    return numChoices;
  }

  private static Pattern C4MoveColumnMatchPattern = Pattern.compile("drop (\\d+)");
  private static Pattern BrkthruMoveCellMatchPattern = Pattern.compile("move (\\d+) (\\d+) (\\d+) (\\d+)");
  private static Pattern HexMoveCellMatchPattern = Pattern.compile("place ([abcdefghi]) (\\d+)");

  private static double[][] moveDistances = null;

  private double[][] generateMoveDistanceMatrix()
  {
    ForwardDeadReckonLegalMoveInfo[] masterMoveList = underlyingStateMachine.getFullPropNet().getMasterMoveList();
    double[][] result = new double[masterMoveList.length][masterMoveList.length];

    for(int fromIndex = 0; fromIndex < masterMoveList.length; fromIndex++)
    {
      int locusColumn = -1;
      int locusX = -1;
      int locusY = -1;

      Pattern pattern = null;
      String moveName = masterMoveList[fromIndex].move.toString();
      Matcher lMatcher = C4MoveColumnMatchPattern.matcher(moveName);
      if (!lMatcher.find() )
      {
        lMatcher = BrkthruMoveCellMatchPattern.matcher(moveName);
        if (!lMatcher.find() )
        {
          lMatcher = HexMoveCellMatchPattern.matcher(moveName);
          if ( lMatcher.find() )
          {
            pattern = HexMoveCellMatchPattern;

            String locusCellX = lMatcher.group(1);
            String locusCellY = lMatcher.group(2);
            locusX = locusCellX.charAt(0) - 'a';
            locusY = Integer.parseInt(locusCellY);
          }
          else
          {
            lMatcher = null;
          }
        }
        else
        {
          pattern = BrkthruMoveCellMatchPattern;

          String locusTargetCellX = lMatcher.group(3);
          String locusTargetCellY = lMatcher.group(4);
          locusX = Integer.parseInt(locusTargetCellX);
          locusY = Integer.parseInt(locusTargetCellY);
        }
      }
      else
      {
        pattern = C4MoveColumnMatchPattern;

        String locusColumnName = lMatcher.group(1);
        locusColumn = Integer.parseInt(locusColumnName);
      }

      for(int toIndex = 0; toIndex < masterMoveList.length; toIndex++)
      {
        if (pattern == null )
        {
          result[fromIndex][toIndex] = 0;
        }
        else
        {
          moveName = masterMoveList[toIndex].move.toString();
          if ( moveName != "noop" )
          {
            lMatcher = pattern.matcher(moveName);
            if (lMatcher.find() )
            {
              int distance = -1;

              if ( pattern == C4MoveColumnMatchPattern )
              {
                assert(locusColumn != -1);
                String moveColumnName = lMatcher.group(1);
                int moveColumn = Integer.parseInt(moveColumnName);
                distance = Math.abs(moveColumn-locusColumn) - 1;//2;

                if ( distance < 0 )
                {
                  distance = 0;
                }
              }
              else if ( pattern == BrkthruMoveCellMatchPattern )
              {
                assert(locusX != -1);
                assert(locusY!= -1);

                String moveSourceCellX = lMatcher.group(1);
                String moveSourceCellY = lMatcher.group(2);
                String moveTargetCellX = lMatcher.group(3);
                String moveTargetCellY = lMatcher.group(4);
                int sourceX = Integer.parseInt(moveSourceCellX);
                int sourceY = Integer.parseInt(moveSourceCellY);
                int sourceDistance = Math.max(Math.abs(sourceX-locusX), Math.abs(sourceY-locusY));
                int targetX = Integer.parseInt(moveTargetCellX);
                int targetY = Integer.parseInt(moveTargetCellY);
                int targetDistance = Math.max(Math.abs(targetX-locusX), Math.abs(targetY-locusY));

                distance = Math.min(sourceDistance, targetDistance);
              }
              else if ( pattern == HexMoveCellMatchPattern )
              {
                assert(locusX != -1);
                assert(locusY!= -1);

                String moveCellX = lMatcher.group(1);
                String moveCellY = lMatcher.group(2);
                int moveX = moveCellX.charAt(0) - 'a';
                int moveY = Integer.parseInt(moveCellY);

                distance = Math.max(Math.abs(moveX-locusX), Math.abs(moveY-locusY));
              }

              assert(distance >= 0);

              result[fromIndex][toIndex] = distance;
            }
          }
        }
      }
    }
    return result;
  }
}
