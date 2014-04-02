
package org.ggp.base.player.gamer.statemachine.sancho;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.player.gamer.statemachine.sample.SampleGamer;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.MobilityHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.PieceHeuristic;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class Sancho extends SampleGamer
{
  public Role            ourRole;
  private final boolean  runSynchronously       = false; //	Set to run everything on one thread to eliminate concurrency issues when debugging
  private int            numRolloutThreads      = (runSynchronously ? 0 : (Runtime.getRuntime().availableProcessors() + 1) / 2);
  private double         minExplorationBias     = 0.5;
  private double         maxExplorationBias     = 1.2;
  private String         planString             = null;
  private Queue<Move>    plan                   = null;
  private int            transpositionTableSize = 2000000;
  Heuristic              mHeuristic             = null;

  @Override
  public void configure(int xiParamIndex, String xiParam)
  {
    // At the moment, Sancho can only be configured with a "plan" of initial
    // moves that it should play - used for testing.
    if (!xiParam.startsWith("plan="))
    {
      throw new InvalidParameterException();
    }
    planString = xiParam.substring(5);
  }

  int netScore(ForwardDeadReckonPropnetStateMachine stateMachine,
                       ForwardDeadReckonInternalMachineState state)
      throws GoalDefinitionException
  {
    ProfileSection methodSection = new ProfileSection("TreeNode.netScore");
    try
    {
      int result = 0;
      int bestEnemyScore = 0;
      for (Role role : stateMachine.getRoles())
      {
        if (!role.equals(ourRole))
        {
          int score = stateMachine.getGoal(state, role);
          if (score > bestEnemyScore)
          {
            bestEnemyScore = score;
          }
        }
        else
        {
          result = stateMachine.getGoal(state, role);
        }
      }

      int winBonus = 0;
      if (result >= bestEnemyScore)
      {
        winBonus += 5;

        if (result > bestEnemyScore)
        {
          winBonus += 5;
        }
      }
      int rawResult = (mctsTree.gameCharacteristics.isPuzzle ? result : ((result + winBonus) * 100) / 110);
      int normalizedResult = ((rawResult - MinRawNetScore) * 100) /
                             (MaxRawNetScore - MinRawNetScore);

      if (normalizedResult > 100 && !overExpectedRangeScoreReported)
      {
        normalizedResult = 100;
        overExpectedRangeScoreReported = true;
        System.out.println("Saw score that nornmalized to > 100");
      }
      else if (normalizedResult < 0 && !underExpectedRangeScoreReported)
      {
        normalizedResult = 0;
        underExpectedRangeScoreReported = true;
        System.out.println("Saw score that nornmalized to < 0");
      }

      return normalizedResult;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  RoleOrdering roleOrdering = null;
  private Move[] canonicallyOrderedMoveBuffer = null;

  private Move[] getMoveCanonicallyOrdered(Move[] move)
  {
    int index = 0;
    boolean processedOurMove = false;

    for (Role role : underlyingStateMachine.getRoles())
    {
      if (role.equals(ourRole))
      {
        canonicallyOrderedMoveBuffer[index++] = move[0];
        processedOurMove = true;
      }
      else
      {
        canonicallyOrderedMoveBuffer[index] = (processedOurMove ? move[index]
                                                               : move[index + 1]);
        index++;
      }
    }

    return canonicallyOrderedMoveBuffer;
  }

  ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private MCTSTree mctsTree = null;

  @Override
  public String getName()
  {
    return "Sancho 1.56a";
  }

  @Override
  public StateMachine getInitialStateMachine()
  {
    if (searchProcessor == null)
    {
      searchProcessor = new TreeSearcher();
      Thread lSearchProcessorThread = new Thread(searchProcessor,
                                                 "Search Processor");
      lSearchProcessorThread.setDaemon(true);
      lSearchProcessorThread.start();
    }
    else
    {
      try
      {
        System.out.println("Stop search processor...");
        searchProcessor.stop();
        System.out.println("...stopped");
      }
      catch (InterruptedException e)
      {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }

    if (rolloutPool != null)
    {
      rolloutPool.stop();
    }

    //GamerLogger.setFileToDisplay("StateMachine");
    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(1 + numRolloutThreads,
                                                                          getRoleName());

    mctsTree = null;

    System.gc();

    return new StateMachineProxy(underlyingStateMachine, searchProcessor);
  }

  int                   numRoles                        = 0;
  private int           MinRawNetScore                  = 0;
  private int           MaxRawNetScore                  = 100;
  private int           multiRoleAverageScoreDiff       = 0;
  private boolean       underExpectedRangeScoreReported = false;
  private boolean       overExpectedRangeScoreReported  = false;
  private MachineState  targetState                     = null;

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

  private void disableGreedyRollouts()
  {
    System.out.println("Disabling greedy rollouts");
    underlyingStateMachine.disableGreedyRollouts();
    if (rolloutPool != null)
    {
      rolloutPool.disableGreedyRollouts();
    }
  }

  RolloutProcessorPool rolloutPool = null;
  private TargetedSolutionStatePlayer puzzlePlayer = null;

  @Override
  public void stateMachineMetaGame(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    Random r = new Random();
    // If have been configured with a plan (for test purposes), load it now.
    // We'll still do everything else as normal, but whilst there are moves in
    // the plan, when it comes to play, we'll just play the specified move.
    plan = null;
    if (planString != null)
    {
      plan = convertPlanString(planString);
    }

    puzzlePlayer = null;
    ourRole = getRole();

    numRoles = underlyingStateMachine.getRoles().size();

    roleOrdering = new RoleOrdering(underlyingStateMachine, ourRole);

    MinRawNetScore = 0;
    MaxRawNetScore = 100;
    underExpectedRangeScoreReported = false;
    overExpectedRangeScoreReported = false;

    rolloutPool = new RolloutProcessorPool(numRolloutThreads, underlyingStateMachine, ourRole);
    rolloutPool.setRoleOrdering(roleOrdering);

    int observedMinNetScore = Integer.MAX_VALUE;
    int observedMaxNetScore = Integer.MIN_VALUE;
    int simulationsPerformed = 0;
    int multiRoleSamples = 0;
    boolean greedyRolloutsDisabled = false;

    multiRoleAverageScoreDiff = 0;

    Set<Heuristic> heuristics = new HashSet<>();
    heuristics.add(new PieceHeuristic());
    mHeuristic = heuristics.iterator().next(); // !! ARR Hack until we can combine heuristics.
    heuristics.add(new MobilityHeuristic());

    for (Heuristic heuristic : heuristics)
    {
      heuristic.tuningInitialise(underlyingStateMachine, roleOrdering);
    }

    ForwardDeadReckonInternalMachineState initialState = underlyingStateMachine.createInternalState(getCurrentState());

    GameCharacteristics gameCharacteristics = new GameCharacteristics(numRoles);

    //	Sample to see if multiple roles have multiple moves available
    //	implying this must be a simultaneous move game
    //	HACK - actually only count games where both players can play the
    //	SAME move - this gets blocker but doesn't include fully factored
    //	games like C4-simultaneous or Chinook (but it's a hack!)
    gameCharacteristics.isSimultaneousMove = false;
    gameCharacteristics.isPseudoSimultaneousMove = false;

    //	Also monitor whether any given player always has the SAME choice of move (or just a single choice)
    //	every turn - such games are (highly probably) iterated games
    List<Set<Move>> roleMoves = new ArrayList<>();
    gameCharacteristics.isIteratedGame = true;

    for (int i = 0; i < numRoles; i++)
    {
      roleMoves.add(null);
    }

    double branchingFactorApproximation = 0;
    int[] roleScores = new int[numRoles];

    //	Perform a small number of move-by-move simulations to assess how
    //	the potential piece count heuristics behave at the granularity of
    //	a single decision
    // !! ARR Just do this until we get near the stopping time.  Combine with the next loop.
    for (int iteration = 0; iteration < 5000; iteration++)
    {
      ForwardDeadReckonInternalMachineState sampleState = new ForwardDeadReckonInternalMachineState(initialState);

      int numRoleMovesSimulated = 0;
      int numBranchesTaken = 0;

      while (!underlyingStateMachine.isTerminal(sampleState))
      {
        boolean roleWithChoiceSeen = false;
        Move[] jointMove = new Move[numRoles];
        Set<Move> allMovesInState = new HashSet<>();

        int choosingRoleIndex = -1;
        for (int i = 0; i < numRoles; i++)
        {
          List<Move> legalMoves = underlyingStateMachine.getLegalMoves(sampleState, roleOrdering.roleIndexToRole(i));

          if (legalMoves.size() > 1)
          {
            Set<Move> previousChoices = roleMoves.get(i);
            HashSet<Move> moveSet = new HashSet<>(legalMoves);

            if (previousChoices != null && !previousChoices.equals(moveSet))
            {
              gameCharacteristics.isIteratedGame = false;
            }
            else
            {
              roleMoves.set(i, moveSet);
            }

            choosingRoleIndex = i;
            for (Move move : legalMoves)
            {
              if (allMovesInState.contains(move))
              {
                gameCharacteristics.isSimultaneousMove = true;
                choosingRoleIndex = -1;
                break;
              }
              allMovesInState.add(move);
            }

            if (roleWithChoiceSeen)
            {
              gameCharacteristics.isPseudoSimultaneousMove = true;
              choosingRoleIndex = -1;
            }

            roleWithChoiceSeen = true;

            numBranchesTaken += legalMoves.size();
            numRoleMovesSimulated++;
          }
          jointMove[roleOrdering.roleIndexToRawRoleIndex(i)] = legalMoves.get(r.nextInt(legalMoves.size()));
        }

        for (Heuristic heuristic : heuristics)
        {
          heuristic.tuningInterimStateSample(sampleState, choosingRoleIndex);
        }

        sampleState = underlyingStateMachine.getNextState(sampleState, jointMove);
      }

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));
      }

      assert(underlyingStateMachine.isTerminal(sampleState));
      for (Heuristic heuristic : heuristics)
      {
        heuristic.tuningTerminalStateSample(sampleState, roleScores);
      }

      branchingFactorApproximation += (numBranchesTaken / numRoleMovesSimulated);
    }

    branchingFactorApproximation /= 50;

    mctsTree = new MCTSTree(underlyingStateMachine,
                            transpositionTableSize,
                            roleOrdering,
                            rolloutPool,
                            gameCharacteristics,
                            mHeuristic);
    if (gameCharacteristics.isSimultaneousMove || gameCharacteristics.isPseudoSimultaneousMove)
    {
      if (!greedyRolloutsDisabled)
      {
        greedyRolloutsDisabled = true;
        disableGreedyRollouts();
      }
    }

    //	Simulate and derive a few basic stats:
    //	1) Is the game a puzzle?
    //	2) For each role what is the largest and the smallest score that seem reachable and what are the corresponding net scores
    long simulationStartTime = System.currentTimeMillis();
    long simulationStopTime = Math.min(timeout - 5000,
                                       simulationStartTime + 10000);

    int[] rolloutStats = new int[2];
    int maxNumTurns = 0;
    int minNumTurns = Integer.MAX_VALUE;
    double averageBranchingFactor = 0;
    double averageNumTurns = 0;
    double averageSquaredNumTurns = 0;

    while (System.currentTimeMillis() < simulationStopTime)
    {
      simulationsPerformed++;

      underlyingStateMachine.getDepthChargeResult(initialState,
                                                  getRole(),
                                                  rolloutStats,
                                                  null,
                                                  null);

      int netScore = netScore(underlyingStateMachine, null);

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));

        if (i != 0 && mctsTree.gameCharacteristics.isMultiPlayer)
        {
          //	If there are several enemy players involved extract a measure
          //	of their goal correlation
          for (Role role2 : underlyingStateMachine.getRoles())
          {
            if (!role2.equals(ourRole) && !role2.equals(roleOrdering.roleIndexToRole(i)))
            {
              int role2Score = underlyingStateMachine.getGoal(role2);

              multiRoleSamples++;
              multiRoleAverageScoreDiff += Math.abs(role2Score - roleScores[i]);
            }
          }
        }
      }

      ForwardDeadReckonInternalMachineState finalState = underlyingStateMachine.getCurrentState();

      for (Heuristic heuristic : heuristics)
      {
        heuristic.tuningTerminalStateSample(finalState, roleScores);
      }

      averageNumTurns = (averageNumTurns * (simulationsPerformed - 1) + rolloutStats[0]) /
                        simulationsPerformed;
      averageSquaredNumTurns = (averageSquaredNumTurns *
                                (simulationsPerformed - 1) + rolloutStats[0] *
                                                             rolloutStats[0]) /
                               simulationsPerformed;
      if (rolloutStats[0] < minNumTurns)
      {
        minNumTurns = rolloutStats[0];
      }
      if (rolloutStats[0] > maxNumTurns)
      {
        maxNumTurns = rolloutStats[0];
      }
      averageBranchingFactor = (averageBranchingFactor *
                                (simulationsPerformed - 1) + rolloutStats[1]) /
                               simulationsPerformed;

      //System.out.println("Saw score of " + netScore);
      if (netScore < observedMinNetScore)
      {
        observedMinNetScore = netScore;
      }

      if (netScore > observedMaxNetScore)
      {
        observedMaxNetScore = netScore;
      }
    }

    // Complete heuristic tuning and identify those which should be enabled.
    boolean lUsingPieceHeuristic = false;
    {
      Set<Heuristic> tunedHeuristics = new HashSet<>();
      for (Heuristic heuristic : heuristics)
      {
        heuristic.tuningComplete();
        if (heuristic.isEnabled())
        {
          tunedHeuristics.add(heuristic);
          lUsingPieceHeuristic = lUsingPieceHeuristic || (heuristic instanceof PieceHeuristic);
        }
      }
      heuristics = tunedHeuristics;
      tunedHeuristics = null;
    }

    System.out.println("branchingFactorApproximation = " +
                       branchingFactorApproximation +
                       ", averageBranchingFactor = " + averageBranchingFactor);
    //	Massive hack - assume that a game longer than 30 turns is not really an iterated game unless it's of fixed length
    if (gameCharacteristics.isIteratedGame &&
        (Math.abs(branchingFactorApproximation - averageBranchingFactor) > 0.1 || (maxNumTurns > 30 && maxNumTurns != minNumTurns)))
    {
      gameCharacteristics.isIteratedGame = false;
    }

    //  Dump the game characteristics to trace output
    gameCharacteristics.report();

    double stdDevNumTurns = Math.sqrt(averageSquaredNumTurns -
                                      averageNumTurns * averageNumTurns);

    System.out.println("Range of lengths of sample games seen: [" +
                       minNumTurns + "," + maxNumTurns +
                       "], branching factor: " + averageBranchingFactor);
    System.out.println("Average num turns: " + averageNumTurns);
    System.out.println("Std deviation num turns: " + stdDevNumTurns);

    mctsTree.explorationBias = 18 / (averageNumTurns + ((maxNumTurns + minNumTurns) / 2 - averageNumTurns) *
                                              stdDevNumTurns / averageNumTurns) + 0.4;
    if (mctsTree.explorationBias < 0.5)
    {
      mctsTree.explorationBias = 0.5;
    }
    else if (mctsTree.explorationBias > 1.2)
    {
      mctsTree.explorationBias = 1.2;
    }

    if (lUsingPieceHeuristic)
    {
      //	Empirically games with piece count heuristics seem to like lower
      //	exploration bias - not entirely sure why!
      mctsTree.explorationBias = mctsTree.explorationBias * 0.6;
    }

    minExplorationBias = mctsTree.explorationBias * 0.8;
    maxExplorationBias = mctsTree.explorationBias * 1.2;

    System.out.println("Set explorationBias range to [" + minExplorationBias +
                       ", " + maxExplorationBias + "]");

    if ( mctsTree.enableMoveActionHistory && (maxNumTurns - minNumTurns) > averageNumTurns / 10)
    {
      mctsTree.moveActionHistoryBias = averageBranchingFactor / 5;
    }
    else
    {
      mctsTree.moveActionHistoryBias = 0;
    }

    System.out
        .println("Set moveActionHistoryBias to " + mctsTree.moveActionHistoryBias);

    if (underlyingStateMachine.numRolloutDecisionNodeExpansions > 0)
    {
      System.out
          .println("Greedy rollout terminal discovery effectiveness: " +
                   (underlyingStateMachine.greedyRolloutEffectiveness * 100) /
                   underlyingStateMachine.numRolloutDecisionNodeExpansions);
      System.out.println("Num terminal props seen: " +
                         underlyingStateMachine.getNumTerminatingMoveProps() +
                         " out of " +
                         underlyingStateMachine.getBasePropositions().size());
    }

    if (simulationsPerformed > 100)
    {
      if (multiRoleSamples > 0)
      {
        multiRoleAverageScoreDiff /= multiRoleSamples;
      }
    }
    else
    {
      observedMinNetScore = 0;
      observedMaxNetScore = 100;
      multiRoleAverageScoreDiff = 0;
    }

    double greedyRolloutCost = (underlyingStateMachine.numRolloutDecisionNodeExpansions == 0 ? 0
                                                                                            : averageBranchingFactor *
                                                                                              (1 - underlyingStateMachine.greedyRolloutEffectiveness /
                                                                                                   (underlyingStateMachine.numRolloutDecisionNodeExpansions)));

    System.out.println("Estimated greedy rollout cost: " + greedyRolloutCost);
    if (minNumTurns == maxNumTurns ||
        ((greedyRolloutCost > 8 || stdDevNumTurns < 0.15 * averageNumTurns || underlyingStateMachine.greedyRolloutEffectiveness < underlyingStateMachine.numRolloutDecisionNodeExpansions / 3) && !mctsTree.gameCharacteristics.isPuzzle))
    {
      if (!greedyRolloutsDisabled)
      {
        greedyRolloutsDisabled = true;
        disableGreedyRollouts();

        //	Scale up the estimate of simulation rate since we'll be running without the overhead
        //	of greedy rollouts (which is proportional to the branching factor)
        simulationsPerformed *= (1 + greedyRolloutCost);
      }
    }

    //	Special case handling for puzzles with hard-to-find wins
    //	WEAKEN THIS WHEN WE HAVE TRIAL A*
    if (mctsTree.gameCharacteristics.isPuzzle && observedMinNetScore == observedMaxNetScore &&
        observedMaxNetScore < 100)
    {
      //	8-puzzle type stuff
      System.out.println("Puzzle with no observed solution");

      MachineState terminalState;
      Set<MachineState> goalStates = underlyingStateMachine
          .findGoalStates(getRole(), 90, 100, 20);
      //Set<MachineState> goalStates = underlyingStateMachine.findTerminalStates(100,20);
      Set<MachineState> cleanedStates = new HashSet<>();

      for (MachineState state : goalStates)
      {
        Set<GdlSentence> eliminatedSentences = new HashSet<>();

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

        MachineState cleaned = new MachineState(new HashSet<>(state.getContents()));
        cleaned.getContents().removeAll(eliminatedSentences);

        cleanedStates.add(cleaned);
      }

      if (!cleanedStates.isEmpty())
      {
        terminalState = cleanedStates.iterator().next();

        System.out.println("Found target state: " + terminalState);

        int targetStateSize = terminalState.getContents().size();

        if (targetStateSize < Math.max(2, initialState.size() / 2))
        {
          System.out
              .println("Unsuitable target state based on state elimination - ignoring");
        }
        else
        {
          puzzlePlayer = new TargetedSolutionStatePlayer(underlyingStateMachine, this, roleOrdering);
          puzzlePlayer.setTargetState(terminalState);
        }
      }
    }

    System.out.println("Min raw score = " + observedMinNetScore + ", max = " +
                       observedMaxNetScore);
    System.out.println("multiRoleAverageScoreDiff = " +
                       multiRoleAverageScoreDiff);

    if (observedMinNetScore == observedMaxNetScore)
    {
      observedMinNetScore = 0;
      observedMaxNetScore = 100;

      System.out
          .println("No score discrimination seen during simulation - resetting to [0,100]");
    }

    if (mctsTree.gameCharacteristics.isPuzzle)
    {
      observedMinNetScore = 0;
      observedMaxNetScore = 100;

      System.out.println("Game is a puzzle so not normalizing scores");
    }

    //	Normalize score ranges
    MinRawNetScore = observedMinNetScore;
    MaxRawNetScore = observedMaxNetScore;
    multiRoleAverageScoreDiff = (multiRoleAverageScoreDiff * 100) /
                                (MaxRawNetScore - MinRawNetScore);

    if (numRolloutThreads == 0)
    {
      mctsTree.rolloutSampleSize = 1;
    }
    else
    {
      mctsTree.rolloutSampleSize = (int)(simulationsPerformed /
                                (2.5 * (simulationStopTime - simulationStartTime)) + 1);
      if (mctsTree.rolloutSampleSize > 100)
      {
        mctsTree.rolloutSampleSize = 100;
      }
    }

    System.out
        .println(simulationsPerformed *
                 1000 /
                 (simulationStopTime - simulationStartTime) +
                 " simulations/second performed - setting rollout sample size to " +
                 mctsTree.rolloutSampleSize);

    if (ProfilerContext.getContext() != null)
    {
      GamerLogger.log("GamePlayer", "Profile stats: \n" +
                                    ProfilerContext.getContext().toString());
    }

    if ((!gameCharacteristics.isIteratedGame || numRoles != 2) && targetState == null)
    {
      mctsTree.root = mctsTree.allocateNode(underlyingStateMachine, initialState, null);
      mctsTree.root.decidingRoleIndex = numRoles - 1;

      if (runSynchronously)
      {
        mctsTree.explorationBias = (maxExplorationBias + minExplorationBias) / 2;
      }
      searchProcessor
          .startSearch(System.currentTimeMillis() + 60000,
                       new ForwardDeadReckonInternalMachineState(initialState));

      try
      {
        Thread.sleep(Math.max(timeout - 3000 - System.currentTimeMillis(), 0));
      }
      catch (InterruptedException e)
      {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }

  class TreeSearcher implements Runnable, ActivityController
  {
    private volatile long    moveTime;
    private volatile long    startTime;
    private volatile int     searchSeqRequested  = 0;
    private volatile int     searchSeqProcessing = 0;
    private volatile boolean stopRequested       = true;
    private volatile boolean running             = false;
    private int              numIterations       = 0;
    private volatile boolean  requestYield        = false;

    @Override
    public void run()
    {
      if (runSynchronously)
      {
        return;
      }

      // TODO Auto-generated method stub
      try
      {
        while (searchAvailable())
        {
          try
          {
            boolean complete = false;

            System.out.println("Move search started");
            //int validationCount = 0;

            //Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

            while (!complete && !stopRequested)
            {
              long time = System.currentTimeMillis();
              double percentThroughTurn = Math
                  .min(100, (time - startTime) * 100 / (moveTime - startTime));

              //							if ( Math.abs(lastPercentThroughTurn - percentThroughTurn) > 4 )
              //							{
              //								System.out.println("Percent through turn: " + percentThroughTurn + " - num iterations: " + numIterations + ", root has children=" + (root.children != null));
              //								lastPercentThroughTurn = percentThroughTurn;
              //							}
              if (requestYield)
              {
                Thread.yield();
              }
              else
              {
                complete = mctsTree.growTree(maxExplorationBias -
                                             percentThroughTurn *
                                             (maxExplorationBias - minExplorationBias) /
                                             100);

              }
            }

            System.out.println("Move search complete");
          }
          catch (TransitionDefinitionException | MoveDefinitionException
              | GoalDefinitionException e)
          {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }
      }
      catch (InterruptedException e)
      {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }

    public int getNumIterations()
    {
      return numIterations;
    }

    private boolean searchAvailable() throws InterruptedException
    {
      synchronized (this)
      {
        if (searchSeqRequested == searchSeqProcessing || stopRequested)
        {
          running = false;
          this.notify();
          this.wait();
        }

        searchSeqProcessing = searchSeqRequested;
        running = true;
      }

      return true;
    }

    public void startSearch(long moveTimeout,
                            ForwardDeadReckonInternalMachineState startState)
    {
      System.out.println("Start move search...");
      synchronized (this)
      {
        moveTime = moveTimeout;
        startTime = System.currentTimeMillis();
        searchSeqRequested++;
        stopRequested = false;
        numIterations = 0;

        mHeuristic.newTurn(mctsTree.root.state, mctsTree.root);

        this.notify();
      }
    }

    public void stop() throws InterruptedException
    {
      synchronized (this)
      {
        stopRequested = true;

        if (running)
        {
          wait();
        }
      }
    }

    @Override
    public void requestYield(boolean state)
    {
      requestYield = state;
    }

    @Override
    public Object getSerializationObject()
    {
      return mctsTree.getSerializationObject();
    }
  }

  TreeSearcher searchProcessor = null;

  @Override
  public Move stateMachineSelectMove(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    // We get the current start time
    long start = System.currentTimeMillis();
    long finishBy = timeout - 2500;
    Move bestMove;
    List<Move> moves;

    if (ProfilerContext.getContext() != null)
    {
      ProfilerContext.getContext().resetStats();
    }

    ForwardDeadReckonInternalMachineState currentState;

    searchProcessor.requestYield(true);

    System.out.println("Calculating current state, current time: " +
                       System.currentTimeMillis());

    synchronized (mctsTree.getSerializationObject())
    {
      currentState = underlyingStateMachine
          .createInternalState(getCurrentState());
      moves = underlyingStateMachine.getLegalMoves(currentState, ourRole);

      //System.out.println("Received current state: " + getCurrentState());
      //System.out.println("Using current state: " + currentState);

      rolloutPool.lowestRolloutScoreSeen = 1000;
      rolloutPool.highestRolloutScoreSeen = -100;

      if (underlyingStateMachine.isTerminal(currentState))
      {
        System.out.println("Asked to search in terminal state!");
      }
    }

    System.out.println("Setting search root, current time: " +
                       System.currentTimeMillis());

    if ((plan != null) && (!plan.isEmpty()))
    {
      // We have a pre-prepared plan.  Simply play the next move.
      bestMove = plan.remove();
      System.out.println("Playing pre-planned move: " + bestMove);
    }
    else if (mctsTree.gameCharacteristics.isIteratedGame && numRoles == 2)
    {
      IteratedGamePlayer iteratedPlayer = new IteratedGamePlayer(underlyingStateMachine, this, mctsTree.gameCharacteristics.isPseudoSimultaneousMove, roleOrdering, mctsTree.competitivenessBonus);
      bestMove = iteratedPlayer.selectMove(moves, timeout);
      System.out.println("Playing best iterated game move: " + bestMove);
    }
    else if (puzzlePlayer != null)
    {
      //bestMove = selectAStarMove(moves, timeout);
      bestMove = puzzlePlayer.selectMove(moves, timeout);
      System.out.println("Playing best puzzle move: " + bestMove);
    }
    else
    {
      //emptyTree();
      //root = null;
      //validateAll();
      mctsTree.setRootState(currentState);

      searchProcessor.startSearch(finishBy, currentState);

      searchProcessor.requestYield(false);

      System.out.println("Waiting for processing, current time: " +
                         System.currentTimeMillis());

      try
      {
        while (System.currentTimeMillis() < finishBy && !mctsTree.root.complete)
        {
          if (runSynchronously)
          {
            mctsTree.growTree((minExplorationBias + maxExplorationBias)/2);
          }
          else
          {
            Thread.sleep(250);
          }
        }
      }
      catch (InterruptedException e)
      {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      if ( mctsTree.root.complete )
      {
        System.out.println("Complete root");
      }

      System.out.println("Timer expired, current time: " +
                         System.currentTimeMillis());

      searchProcessor.requestYield(true);

      //validateAll();
      bestMove = mctsTree.getBestMove();
      System.out.println("Num iterations: " +
          searchProcessor.getNumIterations());
      System.out.println("Heuristic bias: " + mHeuristic.getSampleWeight());

      if (!moves.contains(bestMove))
      {
        System.out.println("Selected illegal move!!");
      }
      System.out.println("Playing move: " + bestMove);

      searchProcessor.requestYield(false);

      //validateAll();
    }

    if (ProfilerContext.getContext() != null)
    {
      GamerLogger.log("GamePlayer", "Profile stats: \n" +
                                    ProfilerContext.getContext().toString());
    }

    // We get the end time
    // It is mandatory that stop<timeout
    long stop = System.currentTimeMillis();

    Thread.currentThread().setPriority(Thread.NORM_PRIORITY);

    System.out.println("Move took: " + (stop - start));

    if (bestMove == null)
    {
      System.out.println("NO MOVE FOUND!");
      System.exit(0);
    }
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
      jointMoves.add(new ArrayList<>(partialJointMove));
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
    List<Move> partialJointMove = new ArrayList<>();

    flattenMoveSubLists(legalMoves, 0, jointMoves, partialJointMove);
  }

  public void setNumThreads(int numThreads)
  {
    numRolloutThreads = numThreads;
  }

  public void setTranspositionTableSize(int tableSize)
  {
    transpositionTableSize = tableSize;
  }
}
