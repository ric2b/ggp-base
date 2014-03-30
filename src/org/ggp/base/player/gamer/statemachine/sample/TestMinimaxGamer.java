
package org.ggp.base.player.gamer.statemachine.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponentFactory;
import org.ggp.base.util.propnet.polymorphic.runtimeOptimized.RuntimeOptimizedComponentFactory;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

public class TestMinimaxGamer extends SampleGamer
{

  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private Set<MachineState>                        visitedStates = new HashSet<MachineState>();
  private int                                      nodesExamined;
  private List<MachineState>                       statesVisited = new LinkedList<MachineState>();
  private HashMap<MachineState, Integer>           considered    = new HashMap<MachineState, Integer>();

  private enum HeuristicType {
    HEURISTIC_TYPE_EXPLORE_AWAY, HEURISTIC_TYPE_EXPLORE_NEW, HEURISTIC_TYPE_GOAL_PROXIMITY, HEURISTIC_TYPE_GOAL_VALUE, HEURISTIC_TYPE_INFERRED_PROPOSITION_VALUE
  };

  // This is the default State Machine
  @Override
  public StateMachine getInitialStateMachine()
  {
    GamerLogger.setFileToDisplay("StateMachine");
    bestScoreGoaled = -1;
    underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine();
    //underlyingStateMachine = new ProverStateMachine();
    //underlyingStateMachine = new TestPropnetStateMachine(new LearningComponentFactory());
    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    //return new CachedStateMachine(underlyingStateMachine);
    return underlyingStateMachine;
  }

  /**
   * This function is called at the start of each round You are required to
   * return the Move your player will play before the timeout.
   */
  private MachineState              terminalState                 = null;
  private MachineState              goalState                     = null;
  private MachineState              nextGoalState                 = null;
  private int                       bestScoreGoaled               = -1;
  private Map<GdlSentence, Integer> terminalSentenceVisitedCounts = null;
  private int                       bestWeightedExplorationResult = -1;
  private Set<GdlSentence>          targetPropositions            = null;
  private HeuristicType             nextExploreType               = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
  private boolean                   isPuzzle                      = false;
  private Map<GdlSentence, Integer> propositionWeights            = new HashMap<GdlSentence, Integer>();

  private void determinePropositionWeights(long timeout, int sampleDepth)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    //	Try to infer heuristic weights for propositions from simulation
    long finishBy = timeout;
    int maxScore = -10000;
    int minScore = 10000;

    MachineState sampleState = new MachineState(new HashSet<GdlSentence>());
    Map<GdlSentence, Integer> propositionCounts = new HashMap<GdlSentence, Integer>();

    for (GdlSentence s : underlyingStateMachine.getBasePropositions())
    {
      propositionWeights.put(s, 0);
      propositionCounts.put(s, 0);
    }

    while (System.currentTimeMillis() < finishBy)
    {
      int theScore = underlyingStateMachine
          .getDepthChargeResult(getCurrentState(),
                                getRole(),
                                sampleDepth,
                                sampleState,
                                null);
      for (Role role : underlyingStateMachine.getRoles())
      {
        if (!role.equals(getRole()))
        {
          int theirScore = underlyingStateMachine.getGoal(sampleState, role);

          theScore -= theirScore;
        }
      }

      for (GdlSentence s : sampleState.getContents())
      {
        if (s != underlyingStateMachine.getXSentence() &&
            s != underlyingStateMachine.getOSentence())
        {
          int currentValue = propositionWeights.get(s);
          int newValue = currentValue + theScore;

          propositionWeights.put(s, newValue);
          propositionCounts.put(s, propositionCounts.get(s) + 1);
        }

      }
    }

    for (GdlSentence s : propositionWeights.keySet())
    {
      int count = propositionCounts.get(s);
      int value = (count == 0 ? 0 : propositionWeights.get(s) / count);

      propositionWeights.put(s, value);

      System.out.println("Value for " + s + " is: " + value);
      if (value > maxScore)
      {
        //System.out.println("New high: " + value);
        maxScore = value;
      }
      if (value < minScore)
      {
        minScore = value;
      }
    }

    if (maxScore > minScore)
    {
      for (GdlSentence s : propositionWeights.keySet())
      {
        int rawValue = propositionWeights.get(s);
        int normalizedValue = ((rawValue - minScore) * 100) /
                              (maxScore - minScore);

        propositionWeights.put(s, normalizedValue);

        if (rawValue == maxScore)
          System.out.println("Top scoring prop: " + s);
        if (rawValue == minScore)
          System.out.println("Bottom scoring prop: " + s);
      }
    }
  }

  @Override
  public void stateMachineMetaGame(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    //	Try to infer heuristic weights for propositions from simulation
    //determinePropositionWeights(timeout - 1000);
  }

  private Move selectPuzzleMove(List<Move> moves, long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    if (terminalState == null)
    {
      Set<MachineState> goalStates = underlyingStateMachine
          .findGoalStates(getRole(), 99, 100, 20);
      //Set<MachineState> goalStates = underlyingStateMachine.findTerminalStates(100,20);
      Set<MachineState> cleanedStates = new HashSet<MachineState>();

      for (MachineState state : goalStates)
      {
        Set<GdlSentence> eliminatedSentences = new HashSet<GdlSentence>();

        for (GdlSentence s : state.getContents())
        {
          int count = 0;

          for (MachineState secondState : goalStates)
          {
            if (state != secondState &&
                unNormalizedStateDistance(state, secondState) == 1 &&
                !secondState.getContents().contains(s))
            {
              count++;
            }
          }

          if (count > 1)
          {
            eliminatedSentences.add(s);
          }
        }

        MachineState cleaned = new MachineState(new HashSet<GdlSentence>(state.getContents()));
        cleaned.getContents().removeAll(eliminatedSentences);

        cleanedStates.add(cleaned);
      }

      if (cleanedStates.isEmpty())
      {
        terminalState = new MachineState();
      }
      else
      {
        terminalState = cleanedStates.iterator().next();

        System.out.println("Found goal state: " + terminalState);
      }
    }

    int bestScore = -1;
    Move bestMove = null;
    statesVisited.add(getCurrentState());

    if (getCurrentState().equals(goalState))
    {
      System.out.println("Reached goal state: " + goalState);
      goalState = null;
      nextGoalState = null;
    }
    terminalSentenceVisitedCounts = new HashMap<GdlSentence, Integer>();

    for (GdlSentence s : terminalState.getContents())
    {
      int count = 0;

      for (MachineState state : statesVisited)
      {
        if (state.getContents().contains(s))
        {
          count++;
        }
      }

      terminalSentenceVisitedCounts.put(s, count);
    }

    nodesExamined = 0;
    HeuristicType searchType = (terminalState.getContents() == null ? HeuristicType.HEURISTIC_TYPE_GOAL_VALUE
                                                                   : HeuristicType.HEURISTIC_TYPE_GOAL_PROXIMITY);

    for (Move move : moves)
    {
      considered.clear();
      int score = minEval(getCurrentState(), move, -1, 100000, 20, searchType);
      if (score > bestScore)
      {
        bestScore = score;
        bestMove = move;
      }
    }

    if (goalState == null && nextGoalState != null)
    {
      goalState = nextGoalState;
      System.out.println("Set goal state of: " + goalState);
    }

    if (goalState == null && bestScore < bestScoreGoaled)
    {
      bestScore = -1;
      bestMove = null;
      bestWeightedExplorationResult = -1;

      targetPropositions = new HashSet<GdlSentence>();
      for (GdlSentence s : terminalState.getContents())
      {
        if (!getCurrentState().getContents().contains(s))
        {
          targetPropositions.add(s);
        }
      }
      System.out
          .println("Searching for a new state region with explore type " +
                   nextExploreType);

      for (Move move : moves)
      {
        considered.clear();
        int score = minEval(getCurrentState(),
                            move,
                            -1,
                            100000,
                            6,
                            nextExploreType);
        considered.clear();
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
        //	if ( heuristicScore > bestHeuristic )
        //	{
        //		bestHeuristic = heuristicScore;
        //		bestMove = move;
        //	}
        //}
      }

      if (bestMove == null)
      {
        bestMove = moves.get(0);
      }

      goalState = nextGoalState;
    }

    return bestMove;
  }

  @Override
  public Move stateMachineSelectMove(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    // We get the current start time
    long start = System.currentTimeMillis();
    bestSeenHeuristicValue = 0;
    isPuzzle = (underlyingStateMachine.getRoles().size() == 1);

    visitedStates.add(getCurrentState());
    /**
     * We put in memory the list of legal moves from the current state. The
     * goal of every stateMachineSelectMove() is to return one of these moves.
     * The choice of which Move to play is the goal of GGP.
     */
    List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(),
                                                       getRole());

    if (moves.size() == 0)
    {
      System.out.println("NO legal moves in state: " + getCurrentState());
    }
    Move bestMove = null;
    statesVisited.add(getCurrentState());

    nodesExamined = 0;

    int searchDepth = 5;

    if (isPuzzle)
    {
      bestMove = selectPuzzleMove(moves, timeout);
    }
    else
    {
      //determinePropositionWeights(start+2000, searchDepth+2);

      int bestScore = -1;

      for (Move move : moves)
      {
        considered.clear();
        int score = minEval(getCurrentState(),
                            move,
                            -1,
                            100000,
                            searchDepth,
                            HeuristicType.HEURISTIC_TYPE_GOAL_VALUE);
        System.out.println("Move " + move + " scores " + score);
        if (score > bestScore)
        {
          bestScore = score;
          bestMove = move;
        }
      }
    }

    if (bestMove == null)
    {
      System.out.println("NO move selected");
    }
    System.out.println("Examined " + nodesExamined + " nodes.");

    // We get the end time
    // It is mandatory that stop<timeout
    long stop = System.currentTimeMillis();

    /**
     * These are functions used by other parts of the GGP codebase You
     * shouldn't worry about them, just make sure that you have moves,
     * selection, stop and start defined in the same way as this example, and
     * copy-paste these two lines in your player
     */
    notifyObservers(new GamerSelectedMoveEvent(moves, bestMove, stop - start));
    return bestMove;
  }

  private void flattenMoveSubLists(List<List<Move>> legalMoves,
                                   int iFromIndex,
                                   List<List<Move>> jointMoves,
                                   List<Move> partialJointMove)
  {
    if (iFromIndex >= legalMoves.size())
    {
      jointMoves.add(new ArrayList<Move>(partialJointMove));
      return;
    }

    for (Move move : legalMoves.get(iFromIndex))
    {
      if (partialJointMove.size() <= iFromIndex)
      {
        partialJointMove.add(move);
      }
      else
      {
        partialJointMove.set(iFromIndex, move);
      }

      flattenMoveSubLists(legalMoves,
                          iFromIndex + 1,
                          jointMoves,
                          partialJointMove);
    }
  }

  private void flattenMoveLists(List<List<Move>> legalMoves,
                                List<List<Move>> jointMoves)
  {
    List<Move> partialJointMove = new ArrayList<Move>();

    flattenMoveSubLists(legalMoves, 0, jointMoves, partialJointMove);
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

  private int stateDistance(MachineState queriedState, MachineState targetState)
  {
    return 98 * unNormalizedStateDistance(queriedState, targetState) /
           targetState.getContents().size();
  }

  private int stateSetDistance(MachineState queriedState,
                               Set<MachineState> targetStates)
  {
    for (GdlSentence s : queriedState.getContents())
    {
      int matchCount = 0;

      for (MachineState state : targetStates)
      {
        if (state.getContents().contains(s))
        {
          matchCount++;
        }
      }

      int sentenceDistance = 100 - (100 * matchCount) / targetStates.size();
    }

    return 0;//98 - (98*matchCount)/targetState.getContents().size();
  }

  private int bestSeenHeuristicValue = 0;

  private int heuristicValue(MachineState state, HeuristicType type)
  {
    int result = 0;

    try
    {
      if (false)
      {
        for (Role role : getStateMachine().getRoles())
        {
          if (role.equals(getRole()))
          {
            result += getStateMachine().getGoal(state, role);
          }
          else
          {
            result -= getStateMachine().getGoal(state, role) / 2;
          }
        }
      }
      else
      {
        switch (type)
        {
          case HEURISTIC_TYPE_INFERRED_PROPOSITION_VALUE:
            for (GdlSentence s : state.getContents())
            {
              result += propositionWeights.get(s);
            }
            if (result == 0)
            {
              System.out.println("Heuristic score of 0 for state: " + state);
            }
            break;
          case HEURISTIC_TYPE_GOAL_VALUE:
            for (Role role : getStateMachine().getRoles())
            {
              if (role.equals(getRole()))
              {
                result += 2 * getStateMachine().getGoal(state, role);
              }
              else
              {
                result -= getStateMachine().getGoal(state, role) / 2;
              }
            }
            result = (result + 100) / 3;
            break;
          case HEURISTIC_TYPE_GOAL_PROXIMITY:
            result = (100 - stateDistance(state, terminalState));
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

                for (MachineState oldState : statesVisited)
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
                                                                                                terminalState))) / 5;

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
                                                                           terminalState))) / 5;
            break;
        }
      }
      //result = getStateMachine().getLegalMoves(state, getRole()).size();
    }
    catch (GoalDefinitionException e)
    {
      //} catch (MoveDefinitionException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return result;
  }

  private int minEval(MachineState state,
                      Move move,
                      int alpha,
                      int beta,
                      int depth,
                      HeuristicType searchType)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    List<List<Move>> legalMoves = new ArrayList<List<Move>>();

    nodesExamined++;
    if (depth == 0)
    {
      int result = heuristicValue(state, searchType);

      //System.out.println("Score " + result + " for State " + state);
      return result;
    }

    for (Role role : getStateMachine().getRoles())
    {
      if (!role.equals(getRole()))
      {
        legalMoves.add(getStateMachine().getLegalMoves(state, role));
      }
      else
      {
        List<Move> myMoveList = new ArrayList<Move>();

        myMoveList.add(move);
        legalMoves.add(myMoveList);
      }
    }

    //	Now try all possible opponent moves and assume they will choose the joint worst
    List<List<Move>> jointMoves = new LinkedList<List<Move>>();

    flattenMoveLists(legalMoves, jointMoves);

    int worstScore = 100000;

    for (List<Move> jointMove : jointMoves)
    {
      MachineState newState = getStateMachine().getNextState(state, jointMove);
      int score = maxEval(newState, alpha, beta, depth, searchType);
      //System.out.println("maxEval " + jointMove + " had value " + score);

      if (score < worstScore)
      {
        worstScore = score;
      }

      if (score < beta)
      {
        beta = worstScore;
      }
      if (worstScore <= alpha)
      {
        break;
      }
    }

    return worstScore;
  }

  private Map<MachineState, Integer> cachedMaxEvals = null;

  private int maxEval(MachineState state,
                      int alpha,
                      int beta,
                      int depth,
                      HeuristicType searchType)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    int result;

    if (considered.containsKey(state))
    {
      return considered.get(state);
    }
    //if ( cachedMaxEvals == null )
    //{
    //	cachedMaxEvals = new HashMap<MachineState,Integer>();
    //}
    //else if ( cachedMaxEvals.containsKey(state))
    //{
    //	return cachedMaxEvals.get(state);
    //}

    //System.out.println("MaxEval state: " + state);
    if (getStateMachine().isTerminal(state))
    {
      result = getStateMachine().getGoal(state, getRole());
      if (isPuzzle)
      {
        if (result > bestScoreGoaled)
        {
          System.out.println("Encountered terminal node, returning score of " +
                             result + " in state " + state);
          goalState = state;
          bestScoreGoaled = result;
        }
        else if (goalState != null && state.equals(goalState))
        {
          result = bestScoreGoaled;
        }
        else
        {
          result = 0;
        }
      }
    }
    else if (goalState != null && state.equals(goalState))
    {
      System.out.println("Encountered goaled node, returning score of " +
                         bestScoreGoaled);
      result = bestScoreGoaled;
    }
    else
    {
      List<Move> moves = getStateMachine().getLegalMoves(state, getRole());

      int bestScore = -1;

      for (Move move : moves)
      {
        int score = minEval(state, move, alpha, beta, depth - 1, searchType);
        //System.out.println("minEval " + move + " had value " + score);

        if (score > bestScore)
        {
          bestScore = score;

          if (score > alpha)
          {
            alpha = score;
          }
          if (score >= beta)
          {
            break;
          }
        }
      }

      result = bestScore;
    }

    considered.put(state, result);

    return result;
  }
}
