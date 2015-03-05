package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Arrays;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class LocalRegionSearcher
{
  public interface LocalSearchController
  {
    boolean terminateSearch();
  }


  private static final Logger LOGGER       = LogManager.getLogger();

  private final int MAX_BRANCHING_FACTOR = 100;
  private final int MAX_DEPTH            = 20;

  private final ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private ForwardDeadReckonInternalMachineState startingState;
  private final RoleOrdering roleOrdering;
  private final int numRoles;
  private final LocalSearchController controller;

  private boolean                         optionalRoleHasOddDepthParity;

  private final ForwardDeadReckonLegalMoveInfo[][] jointMove;
  private final ForwardDeadReckonLegalMoveInfo[][] chooserMoveChoiceStack;
  private final ForwardDeadReckonInternalMachineState[] childStateBuffer;
  private final ForwardDeadReckonLegalMoveInfo pseudoNoop;

  private int numNodesSearched;
  private int currentDepth;
  private boolean unconstrainedSearch;
  private final int[] moveKillerWeight;


  public LocalRegionSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    RoleOrdering xiRoleOrdering,
                    LocalSearchController xiController)
  {
    underlyingStateMachine = xiUnderlyingStateMachine;
    roleOrdering = xiRoleOrdering;
    controller = xiController;

    numRoles = underlyingStateMachine.getRoles().length;
    jointMove = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH][];
    chooserMoveChoiceStack = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH][];
    childStateBuffer = new ForwardDeadReckonInternalMachineState[MAX_DEPTH];

    pseudoNoop = new ForwardDeadReckonLegalMoveInfo();
    pseudoNoop.isPseudoNoOp = true;

    moveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];
  }

  public void setSearchParameters(
    ForwardDeadReckonInternalMachineState xiStartingState,
    ForwardDeadReckonLegalMoveInfo xiRegionCentre)
  {
    startingState = new ForwardDeadReckonInternalMachineState(xiStartingState);

    jointMove[0] = new ForwardDeadReckonLegalMoveInfo[numRoles];
    jointMove[0][0] = xiRegionCentre;

    currentDepth = 1;

    for(int i = 0; i < moveKillerWeight.length; i++)
    {
      moveKillerWeight[i] = 0;
    }

    unconstrainedSearch = (xiRegionCentre == null);

    LOGGER.info("Starting new search with seed move: " + xiRegionCentre);
  }

  public boolean iterate()
  {
    numNodesSearched = 0;
    LOGGER.info("Local move search beginning for depth " + currentDepth);

    chooserMoveChoiceStack[currentDepth] = new ForwardDeadReckonLegalMoveInfo[MAX_BRANCHING_FACTOR];
    jointMove[currentDepth] = new ForwardDeadReckonLegalMoveInfo[numRoles];
    childStateBuffer[currentDepth] = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());

    for(int optionalRole = 0; optionalRole < numRoles; optionalRole++)
    {
      int searchResult = searchToDepth(startingState, 1, currentDepth, optionalRole);
      if ( searchResult == 0 || searchResult == 100 )
      {
        LOGGER.info("Local search finds win at depth " + currentDepth + " for role " + roleOrdering.roleIndexToRole(1-optionalRole));
        LOGGER.info("Last examined move trace:");
        for(int i = 1; i <= currentDepth; i++)
        {
          LOGGER.info(Arrays.toString(jointMove[i]));
        }
        return true;
      }
    }

    if ( controller.terminateSearch() )
    {
      LOGGER.info("Local search terminated at depth " + currentDepth + " with " + numNodesSearched + " states visited");
    }
    else
    {
      LOGGER.info("Local search completed at depth " + currentDepth + " with " + numNodesSearched + " states visited");
      currentDepth++;
    }

    return false;
  }

  /*
   * Search to specified depth, returning:
   *  0 if role 1 win
   *  100 if role 0 win
   *  else 50
   */
  private int searchToDepth(ForwardDeadReckonInternalMachineState state, int depth, int maxDepth, int optionalRole)
  {
    numNodesSearched++;

    if ( underlyingStateMachine.isTerminal(state))
    {
      int result = underlyingStateMachine.getGoal(state, roleOrdering.roleIndexToRole(0));
      return result;
    }

    if ( depth > maxDepth || controller.terminateSearch() )
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
        numChoices = getLocalMoves(legalMoves, chooserMoveChoiceStack[depth], depth, maxDepth);
        choosingRole = i;
      }
    }

    assert(choosingRole != -1);
    if ( depth == 1 )
    {
      optionalRoleHasOddDepthParity = (choosingRole == optionalRole);
    }

    boolean incomplete = false;

    //  At depth 1 consider the optional tenuki first as a complete result
    //  there will allow cutoff in the MCTS tree (in principal)
    if ( choosingRole == optionalRole && depth == 1 )
    {
      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = pseudoNoop;

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole);

      if ( childValue == (choosingRole == 0 ? 100 : 0) )
      {
        //  Tenuki is a forced win for the optional role!  This means that there is
        //  nothing decisive in the local-search-space
        return 50;
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));

      if ( !incomplete )
      {
        LOGGER.info("Tenuki is a loss for " + (optionalRole == 0 ? "us" : "them") + " at this depth");
      }

      if ( childValue == 50 )
      {
        //  If the optional role gets a draw by tenuki then there is no forced win so
        //  no point searching further
        return 50;
      }
    }

    for(int i = 0; i < numChoices; i++)
    {
      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = chooserMoveChoiceStack[depth][i];

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole);

      if ( childValue == (choosingRole == 0 ? 100 : 0) || (childValue == 50 && choosingRole == optionalRole) )
      {
        //  Complete result.
        //  Note this includes draws for the optional role since we're only interested in forced wins
        //  for the non-optional role
//        LOGGER.info("Complete result: " + childValue + " (" + moveDesc + ")");
        moveKillerWeight[jointMove[depth][choosingRole].masterIndex]++;
        return childValue;
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));
    }

    if ( choosingRole == optionalRole && depth > 1 )
    {
      //  Discount winning tenukis, so only search to avoid loss
      if ( incomplete )
      {
        return 50;
      }

      //  Consider also a pseudo-noop
      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = pseudoNoop;

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole);

      if ( childValue == (choosingRole == 0 ? 100 : 0) )
      {
        //  Complete result
        //LOGGER.info("Complete result: " + childValue + " (" + moveDesc + ")");
        return childValue;
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));
    }
    else if ( numChoices == 0 )
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

  private int heuristicValue(ForwardDeadReckonLegalMoveInfo move, int depth, ForwardDeadReckonLegalMoveInfo previousLocalMove)
  {
    return moveKillerWeight[move.masterIndex];
  }

  private int getLocalMoves(Collection<ForwardDeadReckonLegalMoveInfo> allMoves, ForwardDeadReckonLegalMoveInfo[] localMoves, int depth, int maxDistance)
  {
    int numChoices = 0;

    if ( moveDistances == null )
    {
      moveDistances = generateMoveDistanceMatrix();
    }

    for(ForwardDeadReckonLegalMoveInfo move : allMoves)
    {
      boolean include = true;
      ForwardDeadReckonLegalMoveInfo plyMove = null;

      if ( !unconstrainedSearch )
      {
        for(int i = depth-1; i >= 0; i--)
        {
          if ( i == 0 || optionalRoleHasOddDepthParity != (i%2 == 1))
          {
            for(int j = 0; j < jointMove[i].length; j++)
            {
              if ( jointMove[i][j].inputProposition != null )
              {
                plyMove = jointMove[i][j];
                break;
              }
            }
            if ( plyMove != null )
            {
              include = (moveDistances[plyMove.masterIndex][move.masterIndex] <= maxDistance - i);
              break;
            }
          }
        }
      }

      if ( include )
      {
        int insertAt;
        int heuristicValue = heuristicValue(move, depth, plyMove);
        for(insertAt = 0; insertAt < numChoices; insertAt++)
        {
          if ( heuristicValue > heuristicValue(localMoves[insertAt], depth, plyMove))
          {
            break;
          }
        }

        for(int i = numChoices; i > insertAt; i--)
        {
          localMoves[i] = localMoves[i-1];
        }

        localMoves[insertAt] = move;
        numChoices++;
      }
    }

    return numChoices;
  }

  private static Pattern C4MoveColumnMatchPattern = Pattern.compile("drop (\\d+)");
  private static Pattern BrkthruMoveCellMatchPattern = Pattern.compile("move (\\d+) (\\d+) (\\d+) (\\d+)");
  private static Pattern HexMoveCellMatchPattern = Pattern.compile("place ([abcdefghi]) (\\d+)");

  private int[][] moveDistances = null;

  private int[][] generateMoveDistanceMatrix()
  {
    ForwardDeadReckonLegalMoveInfo[] masterMoveList = underlyingStateMachine.getFullPropNet().getMasterMoveList();
    int[][] result = new int[masterMoveList.length][masterMoveList.length];

    int sourceX[] = new int[masterMoveList.length];
    int sourceY[] = new int[masterMoveList.length];
    int targetX[] = new int[masterMoveList.length];
    int targetY[] = new int[masterMoveList.length];
    Pattern pattern = null;

    for(int index = 0; index < masterMoveList.length; index++)
    {
      String moveName = masterMoveList[index].move.toString();

      if ( pattern == null )
      {
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
            }
          }
          else
          {
            pattern = BrkthruMoveCellMatchPattern;
          }
        }
        else
        {
          pattern = C4MoveColumnMatchPattern;
        }
      }

      if ( pattern == null )
      {
        sourceX[index] = -1;
      }
      else
      {
        Matcher lMatcher = pattern.matcher(moveName);

        if ( lMatcher.find() )
        {
          if ( pattern == C4MoveColumnMatchPattern )
          {
            String locusColumnName = lMatcher.group(1);
            sourceX[index] = Integer.parseInt(locusColumnName);
          }
          else if ( pattern == BrkthruMoveCellMatchPattern )
          {
            String moveSourceCellX = lMatcher.group(1);
            String moveSourceCellY = lMatcher.group(2);
            String moveTargetCellX = lMatcher.group(3);
            String moveTargetCellY = lMatcher.group(4);
            sourceX[index] = Integer.parseInt(moveSourceCellX);
            sourceY[index] = Integer.parseInt(moveSourceCellY);
            targetX[index] = Integer.parseInt(moveTargetCellX);
            targetY[index] = Integer.parseInt(moveTargetCellY);
          }
          else if ( pattern == HexMoveCellMatchPattern )
          {
            String moveCellX = lMatcher.group(1);
            String moveCellY = lMatcher.group(2);
            sourceX[index] = moveCellX.charAt(0) - 'a';
            sourceY[index] = Integer.parseInt(moveCellY);
          }
        }
        else
        {
          sourceX[index] = -1;
        }
      }
    }

    for(int fromIndex = 0; fromIndex < masterMoveList.length; fromIndex++)
    {
      for(int toIndex = 0; toIndex < masterMoveList.length; toIndex++)
      {
        if ( sourceX[fromIndex] == -1 || sourceX[toIndex] == -1 )
        {
          result[fromIndex][toIndex] = 0;
        }
        else
        {
          int distance = 0;

          if ( pattern == C4MoveColumnMatchPattern )
          {
            distance = Math.abs(sourceX[fromIndex]-sourceX[toIndex]) - 1;//2;

            if ( distance < 0 )
            {
              distance = 0;
            }
          }
          else if ( pattern == BrkthruMoveCellMatchPattern )
          {
            boolean toIncreasingY = (sourceY[toIndex] - targetY[toIndex] < 0);
            boolean fromIncreasingY = (sourceY[fromIndex] - targetY[fromIndex] < 0);
            int deltaX = Math.abs(targetX[fromIndex] - sourceX[toIndex]);
            int deltaY = Math.abs(sourceY[toIndex] - targetY[fromIndex]);

            if ( toIncreasingY == fromIncreasingY )
            {
              if ( deltaX <= deltaY )
              {
                //  In cone
                distance = deltaY*2 + 1;
              }
              else
              {
                //  Off cone
                int offConeAmount = (deltaX-deltaY+1)/2;

                distance = (deltaY + offConeAmount)*2 + 1;
              }
            }
            else
            {
              if ( (toIncreasingY && sourceY[toIndex] <= targetY[fromIndex]) || (!toIncreasingY && sourceY[toIndex] >= targetY[fromIndex]) )
              {
                //  Forward
                if ( deltaX <= deltaY )
                {
                  //  Forward cone
                  distance = deltaY + 1;
                }
                else
                {
                  //  Forward off-cone
                  int offConeAmount = (deltaX-deltaY+1)/2;

                  //  If target doesn't move towards the cone then increase the off-cone amount by 1
                  int targetDeltaX = Math.abs(targetX[fromIndex] - targetX[toIndex]);
                  if ( targetDeltaX >= deltaX )
                  {
                    offConeAmount++;
                  }

                  distance = 4*offConeAmount - 1 + deltaY;

                  //int offConeAmount = Math.abs(targetX[fromIndex] - sourceX[toIndex]) - (sourceY[toIndex] - targetY[fromIndex]);

                  //distance = sourceY[toIndex] - targetY[fromIndex] + offConeAmount*2 + 1;
                }
              }
              else
              {
                //  Backward
                if ( deltaX <= deltaY )
                {
                  //  Backward cone
                  distance = 2*(deltaY+1) + 1;
                }
                else
                {
                  //  Backward off-cone
                  int offConeAmount = (deltaX-deltaY+1)/2;

                  //  If target doesn't move towards the cone then increase the off-cone amount by 1
                  int targetDeltaX = Math.abs(targetX[fromIndex] - targetX[toIndex]);
                  if ( targetDeltaX > deltaX )
                  {
                    offConeAmount++;
                  }

                  distance = 4*offConeAmount + 2*(deltaY+1);
                  //distance = sourceY[toIndex] - targetY[fromIndex] + offConeAmount*2 + 1;
                }
              }
            }
          }
          else if ( pattern == HexMoveCellMatchPattern )
          {
            distance = Math.max(Math.abs(sourceX[fromIndex]-sourceX[toIndex]), Math.abs(sourceY[fromIndex]-sourceY[toIndex]));
          }

          assert(distance >= 0);

          result[fromIndex][toIndex] = distance;
        }
      }
    }
    return result;
  }
}
