package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;

import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class TargetedSolutionStatePlayer
{

  private class AStarNode implements Comparable<AStarNode>
  {
    private ForwardDeadReckonInternalMachineState state;
    private AStarNode                             parent;
    private Move                                  move;
    private int                                   pathLength;
    private int                                   heuristicCost = -1;

    public AStarNode(ForwardDeadReckonInternalMachineState state,
                     AStarNode parent,
                     Move move)
    {
      this.state = state;
      this.parent = parent;
      this.move = move;

      pathLength = (parent == null ? 0 : parent.pathLength + 1);
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
      return 0;
      //      if ( heuristicCost == -1 )
      //      {
      //        ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(state);
      //
      //        temp.intersect(targetStateAsInternal);
      //
      //        heuristicCost = targetStateAsInternal.size() - temp.size();
      //      }
      //
      //      return heuristicCost;
    }

    @Override
    public int compareTo(AStarNode o)
    {
      return getPriority() - o.getPriority();
    }
  }

  private PriorityQueue<AStarNode>              AStarFringe           = null;
  private ForwardDeadReckonInternalMachineState targetStateAsInternal = null;
  private List<Move>                            AStarSolutionPath     = null;
  ForwardDeadReckonInternalMachineState         stepStateMask         = null;

  private Move selectAStarMove(List<Move> moves, long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    Move bestMove = moves.get(0);
    int[] numAtDistance = new int[50];

    for (int i = 0; i < 50; i++)
    {
      numAtDistance[i] = 0;
    }

    if (AStarSolutionPath == null)
    {
      if (AStarFringe == null)
      {
        AStarFringe = new PriorityQueue<AStarNode>();

        AStarFringe.add(new AStarNode(underlyingStateMachine
            .createInternalState(gamer.getCurrentState()), null, null));
      }

      stepStateMask = new ForwardDeadReckonInternalMachineState(underlyingStateMachine
          .getInfoSet());

      stepStateMask.clear();
      for (ForwardDeadReckonPropositionInfo info : underlyingStateMachine
          .getInfoSet())
      {
        if (info.sentence.toString().contains("step"))
        {
          stepStateMask.add(info);
        }
      }
      stepStateMask.invert();

      if (targetStateAsInternal == null)
      {
        targetStateAsInternal = underlyingStateMachine
            .createInternalState(targetState);
        targetStateAsInternal.intersect(stepStateMask);
      }

      int largestDequeuePriority = -1;
      int bestGoalFound = -1;

      Set<ForwardDeadReckonInternalMachineState> visitedStates = new HashSet<ForwardDeadReckonInternalMachineState>();

      while (!AStarFringe.isEmpty())
      {
        AStarNode node = AStarFringe.remove();

        if (node.getPriority() > largestDequeuePriority)
        {
          largestDequeuePriority = node.getPriority();

          System.out.println("Now dequeuing estimated cost " +
                             largestDequeuePriority + " (fringe size " +
                             AStarFringe.size() + ")");
        }

        if (underlyingStateMachine.isTerminal(node.getState()))
        {
          int goalValue = underlyingStateMachine.getGoal(node.getState(),
                                                         gamer.getRole());

          if (goalValue > bestGoalFound)
          {
            AStarSolutionPath = new LinkedList<Move>();

            //  Construct solution path
            while (node != null && node.getMove() != null)
            {
              AStarSolutionPath.add(0, node.getMove());
              node = node.getParent();
            }

            if (goalValue == 100)
            {
              //break;
            }
          }
        }

        //  Expand the node and add children to the fringe
        List<Move> childMoves = underlyingStateMachine.getLegalMoves(node
            .getState(), gamer.getRole());

        if (childMoves.size() == 0)
        {
          System.out.println("No child moves found from state: " +
                             node.getState());
        }
        for (Move move : childMoves)
        {
          List<Move> jointMove = new LinkedList<Move>();
          jointMove.add(move);

          ForwardDeadReckonInternalMachineState newState = underlyingStateMachine
              .getNextState(node.getState(), jointMove);
          ForwardDeadReckonInternalMachineState steplessState = new ForwardDeadReckonInternalMachineState(newState);
          steplessState.intersect(stepStateMask);

          if (!visitedStates.contains(steplessState))
          {
            AStarNode newChild = new AStarNode(newState, node, move);
            AStarFringe.add(newChild);

            visitedStates.add(steplessState);

            numAtDistance[newChild.pathLength]++;
          }
        }
      }
    }

    for (int i = 0; i < 50; i++)
    {
      System.out.println("Num states at distance " + i + ": " +
                         numAtDistance[i]);
    }

    bestMove = AStarSolutionPath.remove(0);

    return bestMove;
  }


  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private StateMachineGamer gamer;
  private RoleOrdering roleOrdering;

  private MachineState                   goalState                     = null;
  private int                            goalDepth;
  private MachineState                   nextGoalState                 = null;
  private int                            bestScoreGoaled               = -1;
  private Map<GdlSentence, Integer>      terminalSentenceVisitedCounts = null;
  private HashMap<MachineState, Integer> considered                    = new HashMap<>();
  private MachineState                   targetState                     = null;

  public TargetedSolutionStatePlayer(ForwardDeadReckonPropnetStateMachine stateMachine,
                                     StateMachineGamer gamer,
                                     RoleOrdering roleOrdering)
  {
    underlyingStateMachine = stateMachine;
    this.gamer = gamer;
    this.roleOrdering  = roleOrdering;

    goalState = null;
    nextGoalState = null;
    bestScoreGoaled = -1;
    bestSeenHeuristicValue = 0;

    AStarSolutionPath = null;
    AStarFringe = null;
    targetStateAsInternal = null;
}

  public void setTargetState(MachineState state)
  {
    targetState = state;
  }

  private int unNormalizedStateDistance(MachineState queriedState,
                                        MachineState targetState)
  {
    int matchCount = 0;

    for (GdlSentence s : targetState.getContents())
    {
      if (queriedState.getContents().contains(s))
      {
        matchCount++;
      }
    }

    return targetState.getContents().size() - matchCount;
  }

  private enum HeuristicType {
    HEURISTIC_TYPE_EXPLORE_AWAY, HEURISTIC_TYPE_EXPLORE_NEW, HEURISTIC_TYPE_GOAL_PROXIMITY, HEURISTIC_TYPE_GOAL_VALUE, HEURISTIC_TYPE_INFERRED_PROPOSITION_VALUE
  }

  private HeuristicType     nextExploreType               = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
  private Set<GdlSentence>  targetPropositions            = null;
  private int               bestWeightedExplorationResult = -1;
  private Set<MachineState> visitedStates                 = new HashSet<MachineState>();

  private int stateDistance(MachineState queriedState, MachineState targetState)
  {
    return 98 * unNormalizedStateDistance(queriedState, targetState) /
           targetState.getContents().size();
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
          System.out.println("Found heuristic value of " + result +
                             " (goaled value is " + bestScoreGoaled +
                             ") in state " + state);
          bestSeenHeuristicValue = result;
          if (result > bestScoreGoaled)
          {
            System.out.println("Setting as next goal state");
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
              System.out
                  .println("Setting as next goal state at equal value to a previous one");
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
          //System.out.println("Setting goal state for new region to: " + state);
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
          //System.out.println("Setting goal state for new region to: " + state);
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
    List<Move> pseudoJointMove = new LinkedList<Move>();
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
      System.out.println("Encountered goaled node, returning score of " +
                         bestScoreGoaled);
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
      System.out.println("Reached goal state: " + goalState);
      goalState = null;
      nextGoalState = null;
    }
    else
    {
      System.out.println("Current goal state is: " + goalState);
    }

    terminalSentenceVisitedCounts = new HashMap<GdlSentence, Integer>();

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

      System.out
          .println("Unexpectedly reached goal depth without encountering goal state - current state is: " +
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

      //System.out.println("Best score at depth " + depth + ": " + bestScore);
    }

    System.out.println("Achieved search depth of " + depth);
    System.out.println("Best move: " + bestMove + ": " + bestScore);

    if (goalState == null && nextGoalState != null)
    {
      goalDepth = depth;
      goalState = nextGoalState;
      System.out.println("Set goal state of: " + goalState);
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
      System.out
          .println("Searching for a new state region with explore type " +
                   nextExploreType);

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
          //int heuristicScore = minEval(getCurrentState(), move, -1, 101, 1, HeuristicType.HEURISTIC_TYPE_GOAL_PROXIMITY);

          //System.out.println("Move " + move + " has exploration score: " + score + " with heuristic score " + heuristicScore);
          if (score > bestScore)//&& heuristicScore > 10)
          {
            bestScore = score;
            bestMove = move;
            //bestHeuristic = heuristicScore;
          }
          //else if ( score == bestScore )
          //{
          //  if ( heuristicScore > bestHeuristic )
          //  {
          //    bestHeuristic = heuristicScore;
          //    bestMove = move;
          //  }
          //}
          depth++;
        }
      }

      if (bestMove == null)
      {
        bestMove = moves.get(0);
      }

      goalState = nextGoalState;
      goalDepth = depth;
      System.out.println("New goal state at depth " + goalDepth + ": " +
                         goalState);
    }

    return bestMove;
  }

}
