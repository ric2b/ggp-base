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

  /**
   * @author steve
   * Interface through which the local searcher will notify any forced wins or forced
   * tenuki losses through
   */
  public interface LocalSearchResultConsumer
  {
    /**
     * Process the results of a local search
     * @param results
     */
    void  ProcessLocalSearchResult(LocalSearchResults results);
  }

  private static final Logger LOGGER       = LogManager.getLogger();

  private final int MAX_BRANCHING_FACTOR = 100;
  private final int MAX_DEPTH            = 20;

  private final ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private ForwardDeadReckonInternalMachineState startingState = null;
  private final RoleOrdering roleOrdering;
  private final int numRoles;
  private final LocalSearchController controller;

  private boolean                         optionalRoleHasOddDepthParity;

  private final ForwardDeadReckonLegalMoveInfo[][] jointMove;
  private final ForwardDeadReckonLegalMoveInfo[][] chooserMoveChoiceStack;
  private final ForwardDeadReckonInternalMachineState[] childStateBuffer;
  private final ForwardDeadReckonLegalMoveInfo pseudoNoop;
  private final LocalSearchResultConsumer resultsConsumer;
  private final LocalSearchResults searchResult = new LocalSearchResults();
  private final boolean[] moveIsResponse;
  private final boolean[][] chooserMoveChoiceIsResponse;

  private int numNodesSearched;
  private int currentDepth;
  private int firstSearchedRole;
  private ForwardDeadReckonLegalMoveInfo regionCentre;
  private boolean unconstrainedSearch;
  private final int[] optionalMoveKillerWeight;
  private final int[] NonOptionalMoveKillerWeight;

  private final int[] tenukiLossDepth;
  private final ForwardDeadReckonLegalMoveInfo[] tenukiLossSeeds;

  public LocalRegionSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    RoleOrdering xiRoleOrdering,
                    LocalSearchController xiController,
                    LocalSearchResultConsumer xiResultsConsumer)
  {
    underlyingStateMachine = xiUnderlyingStateMachine;
    roleOrdering = xiRoleOrdering;
    controller = xiController;
    resultsConsumer = xiResultsConsumer;

    numRoles = underlyingStateMachine.getRoles().length;
    jointMove = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH][];
    chooserMoveChoiceStack = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH][];
    childStateBuffer = new ForwardDeadReckonInternalMachineState[MAX_DEPTH];
    tenukiLossDepth = new int[xiUnderlyingStateMachine.getRoles().length];
    tenukiLossSeeds = new ForwardDeadReckonLegalMoveInfo[xiUnderlyingStateMachine.getRoles().length];
    moveIsResponse = new boolean[MAX_DEPTH];
    chooserMoveChoiceIsResponse = new boolean[MAX_DEPTH][];

    pseudoNoop = new ForwardDeadReckonLegalMoveInfo();
    pseudoNoop.isPseudoNoOp = true;

    optionalMoveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];
    NonOptionalMoveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];

    searchResult.searchProvider = this;
  }

  public void decayKillerStatistics()
  {
    LOGGER.info("Decaying killer stats");
    for(int i = 0; i < optionalMoveKillerWeight.length; i++)
    {
      optionalMoveKillerWeight[i] = (optionalMoveKillerWeight[i] + 9)/10;
      NonOptionalMoveKillerWeight[i] = (NonOptionalMoveKillerWeight[i] + 9)/10;
    }

    for(int i = 0; i < tenukiLossSeeds.length; i++)
    {
      tenukiLossSeeds[i] = null;
    }
  }

  public void setSearchParameters(
    ForwardDeadReckonInternalMachineState xiStartingState,
    ForwardDeadReckonLegalMoveInfo xiRegionCentre,
    int choosingRole)
  {
    ForwardDeadReckonInternalMachineState newStartingState = xiStartingState;

    jointMove[0] = new ForwardDeadReckonLegalMoveInfo[numRoles];
    regionCentre = xiRegionCentre;

    currentDepth = 1;
    numNodesSearched = 0;
    firstSearchedRole = choosingRole;

    for(int i = 0; i < tenukiLossDepth.length; i++)
    {
      tenukiLossDepth[i] = MAX_DEPTH;
    }

    unconstrainedSearch = (xiRegionCentre == null);

    startingState = newStartingState;

    LOGGER.info("Starting new search with seed move: " + xiRegionCentre + " and first choosing role " + choosingRole);
  }

  public boolean iterate()
  {
    //LOGGER.info("Local move search beginning for depth " + currentDepth);

    chooserMoveChoiceStack[currentDepth] = new ForwardDeadReckonLegalMoveInfo[MAX_BRANCHING_FACTOR];
    jointMove[currentDepth] = new ForwardDeadReckonLegalMoveInfo[numRoles];
    childStateBuffer[currentDepth] = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
    chooserMoveChoiceIsResponse[currentDepth] = new boolean[MAX_BRANCHING_FACTOR];

    if ( tenukiLossSeeds[firstSearchedRole] != null )
    {
      firstSearchedRole = 1-firstSearchedRole;
      LOGGER.info("Forcing first searched role to " + firstSearchedRole + " due to known possible win for opposing role");
    }

    for(int role = 0; role < numRoles; role++)
    {
      //  We don;t look for forced wins by the player who already has a forced loss if they
      //  tenuki at a lower depth.  This **could** miss a win whose starting move is also
      //  a key defensive move, but trying to allow that case while still not accidentally declaring
      //  forced wins that are actually tenuki losses at a shallower depth is quite hard
//      if ( tenukiLossDepth[1-optionalRole] < currentDepth )
//      {
//        //LOGGER.info("Skipping search for optional role " + roleOrdering.roleIndexToRole(optionalRole));
//        continue;
//      }

      //LOGGER.info("Searching with optional role " + optionalRole);
      int score;
      int optionalRole = 1-(role+firstSearchedRole)%2;
      boolean resultFound = false;

      //  If we know there is a tenuki loss for one player, then before searching for forced wins at this depth for
      //  that player, check that there is no forced win for the opponent with the seed that found the tenuki loss
//      if ( tenukiLossSeeds[1-role] != null )
//      {
//        optionalRole = 1-role;
//        jointMove[0][0] = tenukiLossSeeds[optionalRole];
//        LOGGER.info("Performing safety check search for optional role " + optionalRole + " at depth " + currentDepth);
//        score = searchToDepth(startingState, 1, currentDepth, optionalRole);
//
//        resultFound = (score == (optionalRole==0 ? 0 : 100));
//        if ( resultFound )
//        {
//          LOGGER.info("NOT safe!");
//        }
//      }
      if ( !resultFound )
      {
//        if ( optionalRole == 0 && currentDepth==9 && regionCentre.toString().contains("3 3 3 4"))
//        {
//          System.out.println("!");
//        }
        //optionalRole = role;
        jointMove[0][0] = regionCentre;
        if ( tenukiLossSeeds[1-optionalRole] != null )
        {
          jointMove[0][1] = tenukiLossSeeds[1-optionalRole];
          LOGGER.info("Performing joint search for optional role " + optionalRole + " at depth " + currentDepth);
        }
        else
        {
          jointMove[0][1] = null;
          LOGGER.info("Performing regular search for optional role " + optionalRole + " at depth " + currentDepth);
        }
        score = searchToDepth(startingState, 1, currentDepth, optionalRole);
        resultFound = (score == (optionalRole==0 ? 0 : 100));
      }
      if ( resultFound )
      {
        LOGGER.info("Local search finds win at depth " + currentDepth + " for role " + roleOrdering.roleIndexToRole(1-optionalRole) + " with " + numNodesSearched + " states visited");
        LOGGER.info("Last examined move trace:");
        for(int i = 1; i <= currentDepth; i++)
        {
          LOGGER.info(Arrays.toString(jointMove[i]));
        }

        if ( resultsConsumer != null )
        {
          searchResult.atDepth = currentDepth;
          searchResult.winForRole = 1-optionalRole;
          searchResult.tenukiLossForRole = -1;
          searchResult.seedMove = jointMove[0][0];
          searchResult.jointSearchSecondarySeed = jointMove[0][1];
          searchResult.winningMove = jointMove[1][1-optionalRole];
          searchResult.startState = startingState;
          searchResult.searchRadius = currentDepth;

          resultsConsumer.ProcessLocalSearchResult(searchResult);
        }

        //  Treat the winning move the same as a tenuki loss at the previous level to
        //  force searching relative to it in other alternatives
        for(int i = 0; i < jointMove[1].length; i++)
        {
          if ( jointMove[1][i] != null )
          {
            tenukiLossSeeds[1-optionalRole] = jointMove[1][i];
            break;
          }
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
        if ( depth == 1 && i == 0 )
        {
          optionalRoleHasOddDepthParity = (i != optionalRole);
//          if ( optionalRoleHasOddDepthParity )
//          {
//            LOGGER.info("Non-optional role chooses at depth 1");
//          }
//          else
//          {
//            LOGGER.info("Optional role chooses at depth 1");
//          }
        }
        nonChooserMove = legalMoves.iterator().next();
      }
      else
      {
        if ( depth == 1 && i == 0 )
        {
          optionalRoleHasOddDepthParity = (i == optionalRole);
//          if ( optionalRoleHasOddDepthParity )
//          {
//            LOGGER.info("Non-optional role chooses at depth 1");
//          }
//          else
//          {
//            LOGGER.info("Optional role chooses at depth 1");
//          }
        }
        numChoices = getLocalMoves(legalMoves, chooserMoveChoiceStack[depth], depth, maxDepth);
        choosingRole = i;
      }
    }

    assert(choosingRole != -1);

    boolean incomplete = false;

    //  At depth 1 consider the optional tenuki first as a complete result
    //  there will allow cutoff in the MCTS tree (in principal)
    if ( choosingRole == optionalRole && depth == 1 && tenukiLossDepth[optionalRole] > currentDepth )
    {
      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = pseudoNoop;
      moveIsResponse[depth] = false;

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
        LOGGER.info("Tenuki is a loss for " + (optionalRole == 0 ? "us" : "them") + " at depth " + currentDepth);

        tenukiLossDepth[optionalRole] = currentDepth;
        tenukiLossSeeds[optionalRole] = jointMove[0][0];
        if ( resultsConsumer != null )
        {
          searchResult.atDepth = currentDepth;
          searchResult.winForRole = -1;
          searchResult.tenukiLossForRole = optionalRole;
          searchResult.seedMove = jointMove[0][0];
          searchResult.jointSearchSecondarySeed = jointMove[0][1];
          searchResult.winningMove = null;
          searchResult.startState = startingState;
          searchResult.searchRadius = currentDepth;

          resultsConsumer.ProcessLocalSearchResult(searchResult);
        }
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
      moveIsResponse[depth] = chooserMoveChoiceIsResponse[depth][i];

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole);

      if ( childValue == (choosingRole == 0 ? 100 : 0) || (childValue == 50 && choosingRole == optionalRole) )
      {
//        if ( depth == 1 )
//        {
//          System.out.println("!");
//        }
        //  Complete result.
        //  Note this includes draws for the optional role since we're only interested in forced wins
        //  for the non-optional role
//        LOGGER.info("Complete result: " + childValue + " (" + moveDesc + ")");
        int killerValue = 1<<(currentDepth-depth);
        if ( choosingRole == optionalRole)
        {
          optionalMoveKillerWeight[jointMove[depth][choosingRole].masterIndex] += killerValue;
        }
        else
        {
          NonOptionalMoveKillerWeight[jointMove[depth][choosingRole].masterIndex] += killerValue;
        }
        return (choosingRole == 0 ? 100 : 0);//childValue;
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
      moveIsResponse[depth] = false;

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole);

      if ( childValue == (choosingRole == 0 ? 100 : 0) )
      {
        //assert(depth!=1);
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
      //assert(depth!=1);
      //LOGGER.info("@" + depth + " choosing role " + " - complete result due to all child completion");
      return (choosingRole == 0 ? 0 : 100);
    }

    return 50;
  }

  public int getMoveDistance(ForwardDeadReckonLegalMoveInfo from, ForwardDeadReckonLegalMoveInfo to)
  {
    return moveDistances[from.masterIndex][to.masterIndex];
  }

  private int heuristicValue(ForwardDeadReckonLegalMoveInfo move, int depth, ForwardDeadReckonLegalMoveInfo previousLocalMove, boolean forOptionalRole)
  {
    if ( forOptionalRole )
    {
      return optionalMoveKillerWeight[move.masterIndex];
    }
    return NonOptionalMoveKillerWeight[move.masterIndex];
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
      boolean include;
      boolean isNonResponse = false;
      ForwardDeadReckonLegalMoveInfo plyMove = null;

      if ( !unconstrainedSearch )
      {
        boolean seenNonResponsePly = false;
        boolean terminateTrackBack = false;
        boolean jointSearch = (jointMove[0][1] != null);

        include = false;
        for(int i = depth-1; i >= 0; i--)
        {
//          boolean plyMoveFound = false;
          boolean isDistanceGating = false;
          boolean plyIsResponse = false;
//
          boolean isOptionalRolePly = (optionalRoleHasOddDepthParity == (i%2 == 1));

          if ( i == 0 )
          {
            isDistanceGating = true;
            plyIsResponse = false;
            terminateTrackBack = true;
          }
          else if ( (depth%2==1) == optionalRoleHasOddDepthParity )
          {
            //  Optional role moves are distance gated by any non-optional role
            //  move or the last optional role move
            if ( !isOptionalRolePly || jointSearch || i >= depth-2 )
            {
              isDistanceGating = true;
              plyIsResponse = false;
            }
          }
          else
          {
            //  Non-optional role moves are gated on the last non-response non-optional
            //  role move and the last optional role move
            if ( (!isOptionalRolePly && !moveIsResponse[i]) || (i >= depth-2 && isOptionalRolePly) )
            {
              isDistanceGating = true;
              plyIsResponse = isOptionalRolePly;
              terminateTrackBack = (!isOptionalRolePly && i < depth-1);
            }
          }
//          if ( i == 0 || !isOptionalRolePly || jointSearch)
//          {
//            //  All non-optional role moves are distance gating
//            isDistanceGating = true;
//            plyIsResponse = moveIsResponse[i];
//            seenNonResponsePly = !plyIsResponse;
//          }
//          else if ( i >= depth-2 && isOptionalRolePly )
//          {
//            //  The most recent optional role move is distance gating
//            isDistanceGating = true;
//            plyIsResponse = true;
//          }

          if ( isDistanceGating )
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
//              plyMoveFound = true;
              boolean includeAtThisPly = (moveDistances[plyMove.masterIndex][move.masterIndex] <= maxDistance - i);
              if ( i == 0 && jointSearch )
              {
                includeAtThisPly |= (moveDistances[jointMove[0][1].masterIndex][move.masterIndex] <= maxDistance - i);
              }

              include |= includeAtThisPly;

              if ( includeAtThisPly )
              {
                //  If we include the move due to a ply move that is not just a response
                //  to an optional role move then the overall included move is not a response
                isNonResponse |= !plyIsResponse;
              }
              if ( terminateTrackBack)//seenNonResponsePly && i <= depth-2 )
              {
                break;
              }
            }
//            if ( plyMoveFound && i < depth-1 )
//            {
//              break;
//            }
          }
        }
      }
      else
      {
        include = true;
      }

      if ( include )
      {
        int insertAt;
        int heuristicValue = heuristicValue(move, depth, plyMove, (depth%2==1) == optionalRoleHasOddDepthParity);
//        if ( depth ==1  )
//        {
//          LOGGER.info("Depth 1 move choice " + move + " has killer value " + heuristicValue + " for " + (((depth%2==1) == optionalRoleHasOddDepthParity) ? "optional role" : "non-optional role"));
//        }
        for(insertAt = 0; insertAt < numChoices; insertAt++)
        {
          if ( heuristicValue > heuristicValue(localMoves[insertAt], depth, plyMove, (depth%2==1) == optionalRoleHasOddDepthParity))
          {
            break;
          }
        }

        for(int i = numChoices; i > insertAt; i--)
        {
          localMoves[i] = localMoves[i-1];
          chooserMoveChoiceIsResponse[depth][i] = chooserMoveChoiceIsResponse[depth][i-1];
        }

        localMoves[insertAt] = move;
        chooserMoveChoiceIsResponse[depth][insertAt] = !isNonResponse;
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
