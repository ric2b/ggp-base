package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Arrays;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
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
  private ForwardDeadReckonInternalMachineState choiceFromState = null;
  final RoleOrdering roleOrdering;
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

  private final ForwardDeadReckonLegalMoveSet[][] relevantMoves;

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
    jointMove = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH+1][];
    chooserMoveChoiceStack = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH+1][];
    childStateBuffer = new ForwardDeadReckonInternalMachineState[MAX_DEPTH+1];
    tenukiLossDepth = new int[xiUnderlyingStateMachine.getRoles().length];
    tenukiLossSeeds = new ForwardDeadReckonLegalMoveInfo[xiUnderlyingStateMachine.getRoles().length];
    moveIsResponse = new boolean[MAX_DEPTH+1];
    chooserMoveChoiceIsResponse = new boolean[MAX_DEPTH+1][];
    relevantMoves = new ForwardDeadReckonLegalMoveSet[MAX_DEPTH+1][];

    pseudoNoop = new ForwardDeadReckonLegalMoveInfo();
    pseudoNoop.isPseudoNoOp = true;

    optionalMoveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];
    NonOptionalMoveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];

    searchResult.searchProvider = this;

    for(int i = 0; i <= MAX_DEPTH; i++ )
    {
      chooserMoveChoiceStack[i] = new ForwardDeadReckonLegalMoveInfo[MAX_BRANCHING_FACTOR];
      jointMove[i] = new ForwardDeadReckonLegalMoveInfo[numRoles];
      childStateBuffer[i] = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
      chooserMoveChoiceIsResponse[i] = new boolean[MAX_BRANCHING_FACTOR];
      relevantMoves[i] = new ForwardDeadReckonLegalMoveSet[MAX_DEPTH+1];
      for(int j = 0; j <= MAX_DEPTH; j++)
      {
        relevantMoves[i][j] = new ForwardDeadReckonLegalMoveSet(underlyingStateMachine.getFullPropNet().getActiveLegalProps(0));
      }
    }
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
    ForwardDeadReckonInternalMachineState xiChoiceFromState,
    ForwardDeadReckonLegalMoveInfo xiRegionCentre,
    int choosingRole)
  {
    regionCentre = xiRegionCentre;

    currentDepth = 1;
    numNodesSearched = 0;
    firstSearchedRole = choosingRole;

    for(int i = 0; i < tenukiLossDepth.length; i++)
    {
      tenukiLossDepth[i] = MAX_DEPTH;
    }

    unconstrainedSearch = (xiRegionCentre == null);

    startingState = xiStartingState;
    choiceFromState = xiChoiceFromState;

    LOGGER.info("Starting new search with seed move: " + xiRegionCentre + " and first choosing role " + choosingRole);
  }

  /**
   * Encapsulated iterated deepening search to run synchronously in one call
   * @param xiStartingState
   * @param xiSeed
   * @param maxDepth
   * @return final score (0, 100 , 50).  If 0 or 100 result is a forced win.  50 implies indeterminate
   */
  public int completeResultSearchToDepthFromSeed(
    ForwardDeadReckonInternalMachineState xiStartingState,
    ForwardDeadReckonLegalMoveInfo xiSeed,
    int maxDepth)
  {
    if ( maxDepth > MAX_DEPTH )
    {
      maxDepth = MAX_DEPTH;
    }

    //  For now don't try to use local search - just global scope
    unconstrainedSearch = true;

    //  Search with iterative deepening for each role in turn
    regionCentre = xiSeed;
    numNodesSearched = 0;
    for(int i = 0; i < tenukiLossDepth.length; i++)
    {
      tenukiLossDepth[i] = MAX_DEPTH;
    }

    for(int depth = 1; depth <= maxDepth; depth++)
    {
      currentDepth = depth;

      for(int role = 0; role < numRoles; role++)
      {
        int score = searchToDepth(xiStartingState, depth, maxDepth, role);
        if ( score == (role == 0 ? 0 : 100) )
        {
          return score;
        }
      }
    }

    return 50;
  }

  public boolean iterate()
  {
    //LOGGER.info("Local move search beginning for depth " + currentDepth);

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
//        if ( optionalRole == 0 && currentDepth==3 && regionCentre.toString().contains("5 3 4 4"))
//        {
//          System.out.println("!");
//        }
        //optionalRole = role;
        jointMove[0][0] = regionCentre;
        if ( tenukiLossSeeds[1-optionalRole] != null )
        {
          jointMove[0][1] = tenukiLossSeeds[1-optionalRole];
          LOGGER.info("Performing joint search for optional role " + optionalRole + " at depth " + currentDepth + " with secondary seed " + tenukiLossSeeds[1-optionalRole]);
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
          searchResult.choiceFromState = choiceFromState;
          if ( choiceFromState != null )
          {
            searchResult.winPath = new ForwardDeadReckonLegalMoveInfo[currentDepth+1];
            searchResult.relevantMovesForWin = new ForwardDeadReckonLegalMoveSet[currentDepth+1];
            for( int i = 0; i <= currentDepth; i++)
            {
              searchResult.winPath[i] = jointMove[i][1-optionalRole];
              searchResult.relevantMovesForWin[i] = relevantMoves[1][i];
            }
          }
          else
          {
            searchResult.winPath = null;
            searchResult.relevantMovesForWin = null;
          }

          resultsConsumer.ProcessLocalSearchResult(searchResult);
        }

        //  Treat the winning move the same as a tenuki loss at the previous level to
        //  force searching relative to it in other alternatives
        for(int i = 0; i < jointMove[1].length; i++)
        {
          if ( jointMove[1][i] != null && jointMove[1][i].inputProposition != null )
          {
            tenukiLossSeeds[1-optionalRole] = jointMove[1][i];
            break;
          }
        }

        return true;
      }
    }

    if ( controller != null && controller.terminateSearch() )
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

  private void clearWinPathRelevantMoves(int depth)
  {
    for(int i = depth; i <= currentDepth; i++)
    {
      relevantMoves[depth][i].clear();
    }
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

    if ( depth > maxDepth || (controller != null && controller.terminateSearch()) )
    {
      return 50;
    }

    clearWinPathRelevantMoves(depth);

    int choosingRole = -1;
    int numChoices = 0;
    ForwardDeadReckonLegalMoveInfo nonChooserMove = null;
    boolean tenukiPossible = true;

    for(int i = 0; i < numRoles; i++)
    {
      Role role = roleOrdering.roleIndexToRole(i);
      Collection<ForwardDeadReckonLegalMoveInfo> legalMoves = underlyingStateMachine.getLegalMoves(state, role);

      if ( legalMoves.iterator().next().inputProposition == null )
      {
        if ( depth == 1 && i == 0 )
        {
          optionalRoleHasOddDepthParity = (i != optionalRole);

          //  Wins can only occur on the move of the putative winning player so we
          //  cannot have a forced win for the non-optional player with a given
          //  max search depth that was not findable at a lesser depth unless the
          //  max depth is such that the final ply choice is the non-optional player
          if ( (maxDepth%2 == 0) != optionalRoleHasOddDepthParity )
          {
            return 50;
          }
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

          //  Wins can only occur on the move of the putative winning player so we
          //  cannot have a forced win for the non-optional player with a given
          //  max search depth that was not findable at a lesser depth unless the
          //  max depth is such that the final ply choice is the non-optional player
          if ( (maxDepth%2 == 0) != optionalRoleHasOddDepthParity )
          {
            return 50;
          }
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
        tenukiPossible = (numChoices < legalMoves.size());
      }
    }

    assert(choosingRole != -1);

    boolean incomplete = false;

    //  At depth 1 consider the optional tenuki first as a complete result
    //  there will allow cutoff in the MCTS tree (in principal)
    if ( choosingRole == optionalRole && tenukiPossible && tenukiLossDepth[optionalRole] > currentDepth )
    {
      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = pseudoNoop;
      moveIsResponse[depth] = false;

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole);

      if ( childValue != (optionalRole == 0 ? 0 : 100) )
      {
        //  Tenuki is not a forced win for the non-optional role.  This means that there is
        //  nothing decisive in the local-search-space
        return (choosingRole == 0 ? 100 : 0);
      }

      if ( depth == 1 )
      {
        tenukiLossDepth[optionalRole] = currentDepth;
        tenukiLossSeeds[optionalRole] = jointMove[0][0];

        if ( resultsConsumer != null )
        {
          LOGGER.info("Tenuki is a loss for " + (optionalRole == 0 ? "us" : "them") + " at depth " + currentDepth);

          searchResult.atDepth = currentDepth;
          searchResult.winForRole = -1;
          searchResult.tenukiLossForRole = optionalRole;
          searchResult.seedMove = jointMove[0][0];
          searchResult.jointSearchSecondarySeed = jointMove[0][1];
          searchResult.winningMove = null;
          searchResult.startState = startingState;
          searchResult.searchRadius = currentDepth;
          searchResult.winPath = null;
          searchResult.choiceFromState = null;
          searchResult.relevantMovesForWin = null;

          resultsConsumer.ProcessLocalSearchResult(searchResult);
        }
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

          if ( depth < maxDepth )
          {
            //  This is the path we would take from here so it is relevant to the solution
            relevantMoves[depth][depth].add(jointMove[depth][choosingRole]);
            //  As are all the descendant relevant moves found in solving this node
            for(int j = depth+1; j <= maxDepth; j++)
            {
              relevantMoves[depth][j].merge(relevantMoves[depth+1][j]);
            }
          }
        }
        return (choosingRole == 0 ? 100 : 0);//childValue;
      }
      else if ( choosingRole == optionalRole && depth < maxDepth )
      {
        //  This path is one we have to be able to handle from here so it is relevant
        for(int j = depth+1; j <= maxDepth; j++)
        {
          relevantMoves[depth][j].merge(relevantMoves[depth+1][j]);
        }
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));
    }

    if ( numChoices == 0 && choosingRole != optionalRole )
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
    //  If we're joint searching with a secondary seed and a legal move at depth 1 is exactly the
    //  secondary seed move choose it first
    if ( depth == 1 && jointMove[0][1] != null && jointMove[0][1].masterIndex == move.masterIndex )
    {
      return Integer.MAX_VALUE;
    }
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

          if ( i == 0 && (!isOptionalRolePly || depth==1) )
          {
            //  The first non-optional role move is distance gated by the seed, but the first
            //  optional role move is only gated by the seed if it is the first move of the
            //  sequence (optional role moves that are within distance of the seed but are
            //  not dependent on any non-optional role move made need not be searched - they
            //  may potentially be wins for the optional role, but only if they are independently
            //  of the non-optional moves played, and thus discoverable when searched with role
            //  optionality reversed)
            isDistanceGating = true;
            plyIsResponse = false;
            terminateTrackBack = true;
          }
          else if ( (depth%2==1) == optionalRoleHasOddDepthParity )
          {
            //  Optional role moves are distance gated by any non-optional role
            //  move [or the last optional role move?]
            if ( !isOptionalRolePly || jointSearch )//|| i >= depth-2 )
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

    //  Symmetrify
    for(int fromIndex = 0; fromIndex < masterMoveList.length; fromIndex++)
    {
      for(int toIndex = 0; toIndex < masterMoveList.length; toIndex++)
      {
        int fromToDistance = result[fromIndex][toIndex];
        int toFromDistance = result[toIndex][fromIndex];
        int symmetrifiedDistance = Math.min(toFromDistance, fromToDistance);

//        if ( Math.abs(fromToDistance-toFromDistance) > 1)
//        {
//          String moveName1 = masterMoveList[fromIndex].move.toString();
//          String moveName2 = masterMoveList[toIndex].move.toString();
//
//          LOGGER.info("Moves " + moveName1 + " and " + moveName2 + " have initial assymetric distnces of " + fromToDistance + " and " + toFromDistance);
//        }

        result[fromIndex][toIndex] = symmetrifiedDistance;
        result[toIndex][fromIndex] = symmetrifiedDistance;
      }
    }

    return result;
  }
}
