package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class TargetedSolutionStatePlayer
{
  private static final Logger LOGGER = LogManager.getLogger();

  private class AStarNode implements Comparable<AStarNode>
  {
    private ForwardDeadReckonInternalMachineState state;
    private AStarNode                             parent;
    private Move                                  move;
    private int                                   pathLength;
    private int                                   heuristicCost = -1;

    public AStarNode(ForwardDeadReckonInternalMachineState xiState,
                     AStarNode xiParent,
                     Move xiMove)
    {
      this.state = new ForwardDeadReckonInternalMachineState(xiState);
      this.parent = xiParent;
      this.move = xiMove;

      pathLength = (xiParent == null ? 0 : xiParent.pathLength + 1);
    }

    public AStarNode getParent()
    {
      return parent;
    }

    public Move getMove()
    {
      return move;
    }

    public ForwardDeadReckonInternalMachineState getState()
    {
      return state;
    }

    public int getPriority()
    {
      return pathLength + heuristicCost();
    }

    private int heuristicCost()
    {
      if ( heuristicCost == -1 )
      {
        ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(state);

        temp.intersect(targetStateAsInternal);

        heuristicCost = (int)(targetStateAsInternal.size() - temp.size());
      }

      return heuristicCost;
    }

    @Override
    public int compareTo(AStarNode o)
    {
      return getPriority() - o.getPriority();
    }
  }

  /**
   * Target state we must reach to achieve solution
   */
  ForwardDeadReckonInternalMachineState         targetStateAsInternal = null;

  /**
   * Attempt to solve the puzzle using A*
   * @param solutionScoreThreshold score required to be considered as an acceptable solution
   * @param timeout - time to search until (max)
   * @return solution plan if found, else null
   */
  public Collection<Move> attemptAStarSolve(int solutionScoreThreshold, long timeout)
  {
    int bestGoalFound = -1;
    List<Move> AStarSolutionPath     = null;
    PriorityQueue<AStarNode> AStarFringe = new PriorityQueue<>();

    AStarFringe.add(new AStarNode(underlyingStateMachine
        .createInternalState(gamer.getCurrentState()), null, null));

    ForwardDeadReckonInternalMachineState steplessStateMask = underlyingStateMachine.getNonControlMask();

    if (targetStateAsInternal == null)
    {
      targetStateAsInternal = underlyingStateMachine.createInternalState(targetState);
      targetStateAsInternal.intersect(steplessStateMask);
    }

    int largestDequeuePriority = -1;
    int numStatesProcessed = 0;

    Set<ForwardDeadReckonInternalMachineState> AStarVisitedStates = new HashSet<>();
    ForwardDeadReckonLegalMoveInfo[] jointMove = new ForwardDeadReckonLegalMoveInfo[1];
    ForwardDeadReckonInternalMachineState stateBuffer = new ForwardDeadReckonInternalMachineState(steplessStateMask);
    ForwardDeadReckonInternalMachineState steplessStateBuffer = new ForwardDeadReckonInternalMachineState(steplessStateMask);

    while (!AStarFringe.isEmpty() && System.currentTimeMillis() < timeout)
    {
      numStatesProcessed++;
      AStarNode node = AStarFringe.remove();

      if (node.getPriority() > largestDequeuePriority)
      {
        largestDequeuePriority = node.getPriority();
      }

      if (underlyingStateMachine.isTerminal(node.getState()))
      {
        int goalValue = underlyingStateMachine.getGoal(node.getState(),
                                                       gamer.getRole());

        if (goalValue > bestGoalFound)
        {
          AStarSolutionPath = new LinkedList<>();

          //  Construct solution path
          while (node != null && node.getMove() != null)
          {
            AStarSolutionPath.add(0, node.getMove());
            node = node.getParent();
          }

          bestGoalFound = goalValue;
          if (goalValue == 100)
          {
            break;
          }
        }
      }

      //  Expand the node and add children to the fringe
      Collection<ForwardDeadReckonLegalMoveInfo> childMoves = underlyingStateMachine.getLegalMoves(node.getState(), gamer.getRole());

      for (ForwardDeadReckonLegalMoveInfo moveInfo : childMoves)
      {
        jointMove[0] = moveInfo;

        underlyingStateMachine.getNextState(node.getState(), null, jointMove, stateBuffer);

        steplessStateBuffer.copy(stateBuffer);
        steplessStateBuffer.intersect(steplessStateMask);

        if (!AStarVisitedStates.contains(steplessStateBuffer))
        {
          AStarNode newChild = new AStarNode(stateBuffer, node, moveInfo.move);
          AStarFringe.add(newChild);

          AStarVisitedStates.add(new ForwardDeadReckonInternalMachineState(steplessStateBuffer));
        }
      }
    }

    LOGGER.info("A* processed " + numStatesProcessed + " states");
    return (bestGoalFound >= solutionScoreThreshold ? AStarSolutionPath : null);
  }


  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private StateMachineGamer gamer;

  private MachineState                   goalState                     = null;
  private int                            goalDepth;
  private MachineState                   nextGoalState                 = null;
  private int                            bestScoreGoaled               = -1;
  private Map<GdlSentence, Integer>      terminalSentenceVisitedCounts = null;
  private HashMap<MachineState, Integer> considered                    = new HashMap<>();
  private MachineState                   targetState                     = null;

  /**
   * Construct a targeted-state puzzle solution solver for use when the solution state
   * is known
   * @param stateMachine - underlying state machine
   * @param xiGamer - StateMachineGamer from which current state can be queried
   */
  public TargetedSolutionStatePlayer(ForwardDeadReckonPropnetStateMachine stateMachine,
                                     StateMachineGamer xiGamer)
  {
    underlyingStateMachine = stateMachine;
    this.gamer = xiGamer;

    goalState = null;
    nextGoalState = null;
    bestScoreGoaled = -1;
    bestSeenHeuristicValue = 0;

    targetStateAsInternal = null;
}

  /**
   * Set the solution state that this solver is tp try to find a path to
   * @param state
   */
  public void setTargetState(MachineState state)
  {
    targetState = state;
  }

  private static int unNormalizedStateDistance(MachineState fromState,
                                        MachineState toState)
  {
    int matchCount = 0;

    for (GdlSentence s : toState.getContents())
    {
      if (fromState.getContents().contains(s))
      {
        matchCount++;
      }
    }

    return toState.getContents().size() - matchCount;
  }

  private enum HeuristicType {
    HEURISTIC_TYPE_EXPLORE_AWAY, HEURISTIC_TYPE_EXPLORE_NEW, HEURISTIC_TYPE_GOAL_PROXIMITY, HEURISTIC_TYPE_GOAL_VALUE, HEURISTIC_TYPE_INFERRED_PROPOSITION_VALUE
  }

  private HeuristicType     nextExploreType               = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
  private Set<GdlSentence>  targetPropositions            = null;
  private int               bestWeightedExplorationResult = -1;
  private Set<MachineState> visitedStates                 = new HashSet<>();

  private static int stateDistance(MachineState fromState, MachineState toState)
  {
    return 98 * unNormalizedStateDistance(fromState, toState) /
           toState.getContents().size();
  }

  private int bestSeenHeuristicValue = 0;

  private int heuristicValue(MachineState state, HeuristicType type)
  {
    int result = 0;

    switch (type)
    {
      case HEURISTIC_TYPE_GOAL_PROXIMITY:
        result = (100 - stateDistance(state, targetState));
        if (result > bestSeenHeuristicValue)
        {
          LOGGER.info("Found heuristic value of " + result +
                             " (goaled value is " + bestScoreGoaled +
                             ") in state " + state);
          bestSeenHeuristicValue = result;
          if (result > bestScoreGoaled)
          {
            LOGGER.info("Setting as next goal state");
            nextGoalState = state;
            goalState = null;
            bestScoreGoaled = result;
            nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
          }
          else if (result == bestScoreGoaled && goalState == null)
          {
            int explorationResult = 100;

            for (MachineState oldState : visitedStates)
            {
              int distance = unNormalizedStateDistance(state, oldState);

              if (distance < explorationResult)
              {
                explorationResult = distance;
              }
            }

            if (explorationResult > 1)
            {
              LOGGER.info("Setting as next goal state at equal value to a previous one");
              nextGoalState = state;
              goalState = null;
              bestScoreGoaled = result;
              nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
            }
            else if (goalState != null && !state.equals(goalState))
            {
              result = 0;
            }
          }
          else if (goalState != null && !state.equals(goalState))
          {
            result = 0;
          }
        }
        else if (goalState != null && !state.equals(goalState))
        {
          result = 0;
        }
        break;

      case HEURISTIC_TYPE_EXPLORE_AWAY:
        int matchCount = 0;

        for (GdlSentence s : targetPropositions)
        {
          if (state.getContents().contains(s))
          {
            matchCount++;
          }
        }

        result = (4 * (100 * matchCount) / targetPropositions.size() + (100 - stateDistance(state,
                                                                                            targetState))) / 5;

        if (result > bestWeightedExplorationResult)
        {
          //LOGGER.debug("Setting goal state for new region to: ", state);
          bestWeightedExplorationResult = result;
          nextGoalState = state;
          nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_NEW;
        }
        break;

      case HEURISTIC_TYPE_EXPLORE_NEW:
        int weightedExplorationResult = 0;

        for (Entry<GdlSentence, Integer> e : terminalSentenceVisitedCounts
            .entrySet())
        {
          if (state.getContents().contains(e.getKey()))
          {
            int matchValue = (100 * (visitedStates.size() - e.getValue())) /
                             visitedStates.size();

            weightedExplorationResult += matchValue;
          }
        }

        if (weightedExplorationResult > bestWeightedExplorationResult)
        {
          //LOGGER.debug("Setting goal state for new region to: ", state);
          bestWeightedExplorationResult = weightedExplorationResult;
          nextGoalState = state;
        }
        result = (4 * weightedExplorationResult + (100 - stateDistance(state,
                                                                       targetState))) / 5;
        break;

      case HEURISTIC_TYPE_GOAL_VALUE:
        // !! ARR -> SD: This case was missing.
        break;

      case HEURISTIC_TYPE_INFERRED_PROPOSITION_VALUE:
        // !! ARR -> SD: This case was missing.
        break;

      default:
        break;

    }

    return result;
  }

  private int searchTree(MachineState state,
                         Move move,
                         int depth,
                         HeuristicType searchType)
      throws TransitionDefinitionException, GoalDefinitionException,
      MoveDefinitionException
  {
    List<Move> pseudoJointMove = new LinkedList<>();
    pseudoJointMove.add(move);
    int result;

    MachineState nextState = underlyingStateMachine
        .getNextState(state, pseudoJointMove);
    if (considered.containsKey(nextState))
    {
      return considered.get(nextState);
    }

    if (underlyingStateMachine.isTerminal(nextState))
    {
      result = underlyingStateMachine.getGoal(nextState, gamer.getRole());

      if (result > bestScoreGoaled)
      {
        goalState = null;
        nextGoalState = nextState;
        bestScoreGoaled = result;
      }
      else if (goalState != null && nextState.equals(goalState))
      {
        result = bestScoreGoaled;
      }
      else
      {
        result = 0;
      }
    }
    else if (goalState != null && nextState.equals(goalState))
    {
      LOGGER.debug("Encountered goaled node, returning score of " + bestScoreGoaled);
      result = bestScoreGoaled;
    }
    else if (depth == 0)
    {
      result = heuristicValue(nextState, searchType);
    }
    else
    {
      List<Move> moves = underlyingStateMachine.getLegalMoves(nextState,
                                                              gamer.getRole());

      int bestScore = -1;

      for (Move nextMove : moves)
      {
        int score = searchTree(nextState, nextMove, depth - 1, searchType);

        if (score > bestScore)
        {
          bestScore = score;
        }
      }

      result = bestScore;
    }

    considered.put(nextState, result);

    return result;
  }

  /**
   * Select the next move to play
   * @param moves - available moves
   * @param timeout - time to search up to (max)
   * @return best move choice found
   * @throws TransitionDefinitionException
   * @throws MoveDefinitionException
   * @throws GoalDefinitionException
   */
  public Move selectMove(List<Move> moves, long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    long totalTime = timeout - System.currentTimeMillis();
    int bestScore = -1;
    Move bestMove = moves.get(0);
    visitedStates.add(gamer.getCurrentState());

    if (gamer.getCurrentState().equals(goalState))
    {
      LOGGER.info("Reached goal state: " + goalState);
      goalState = null;
      nextGoalState = null;
    }
    else
    {
      LOGGER.info("Current goal state is: " + goalState);
    }

    terminalSentenceVisitedCounts = new HashMap<>();

    for (GdlSentence s : targetState.getContents())
    {
      int count = 0;

      for (MachineState state : visitedStates)
      {
        if (state.getContents().contains(s))
        {
          count++;
        }
      }

      terminalSentenceVisitedCounts.put(s, count);
    }

    int depth = (goalState != null ? --goalDepth : 0);
    HeuristicType searchType = HeuristicType.HEURISTIC_TYPE_GOAL_PROXIMITY;

    if (depth < 0)
    {
      depth = 0;
      goalState = null;

      LOGGER.warn("Unexpectedly reached goal depth without encountering goal state - current state is: " +
                  gamer.getCurrentState());
    }

    bestScore = -1;
    bestMove = null;

    while (System.currentTimeMillis() < timeout - totalTime * 3 / 4 &&
           bestScore < 90)
    {
      depth++;

      for (Move move : moves)
      {
        considered.clear();

        int score = searchTree(gamer.getCurrentState(), move, depth, searchType);
        if (score > bestScore)
        {
          bestScore = score;
          bestMove = move;
        }
      }
    }

    LOGGER.info("Achieved search depth of " + depth);
    LOGGER.info("Best move: " + bestMove + ": " + bestScore);

    if (goalState == null && nextGoalState != null)
    {
      goalDepth = depth;
      goalState = nextGoalState;
      LOGGER.info("Set goal state of: " + goalState);
    }

    if (goalState == null && bestScore <= bestScoreGoaled)
    {
      targetPropositions = new HashSet<>();
      for (GdlSentence s : targetState.getContents())
      {
        if (!gamer.getCurrentState().getContents().contains(s))
        {
          targetPropositions.add(s);
        }
      }
      LOGGER.info("Searching for a new state region with explore type " + nextExploreType);

      depth = 1;

      while (System.currentTimeMillis() < timeout && depth <= 6)
      {
        bestScore = -1;
        bestMove = null;
        bestWeightedExplorationResult = -1;

        for (Move move : moves)
        {
          considered.clear();
          int score = searchTree(gamer.getCurrentState(),
                                 move,
                                 depth,
                                 nextExploreType);

          if (score > bestScore)
          {
            bestScore = score;
            bestMove = move;
          }

          depth++;
        }
      }

      if (bestMove == null)
      {
        bestMove = moves.get(0);
      }

      goalState = nextGoalState;
      goalDepth = depth;
      LOGGER.info("New goal state at depth " + goalDepth + ": " + goalState);
    }

    return bestMove;
  }

}
