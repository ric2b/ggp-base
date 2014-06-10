package org.ggp.base.player.gamer.statemachine.sancho;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.player.gamer.statemachine.sample.SampleGamer;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.CombinedHeuristic;
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
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class Sancho extends SampleGamer
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * When adding additional state, consider any necessary additions to {@link #tidyUp()}.
   */
  public Role                         ourRole;
  private int                         mTurn                           = 0;
  private String                      planString                      = null;
  private GamePlan                    plan                            = null;
  private int                         transpositionTableSize          = MachineSpecificConfiguration.getCfgVal(CfgItem.NODE_TABLE_SIZE, 2000000);
  private RuntimeGameCharacteristics  gameCharacteristics             = null;
  private RoleOrdering                roleOrdering                    = null;
  private Move[]                      canonicallyOrderedMoveBuffer    = null;
  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine = null;
  private int                         numRoles                        = 0;
  private int                         MinRawNetScore                  = 0;
  private int                         MaxRawNetScore                  = 100;
  private int                         multiRoleAverageScoreDiff       = 0;
  private short                       currentMoveDepth                = 0;
  private boolean                     underExpectedRangeScoreReported = false;
  private boolean                     overExpectedRangeScoreReported  = false;
  private TargetedSolutionStatePlayer puzzlePlayer                    = null;
  private GameSearcher                searchProcessor                 = null;
  private String                      mLogName                        = null;
  private SystemStatsLogger           mSysStatsLogger                 = null;
  private static final boolean        ASSERTIONS_ENABLED;
  private static final int            MIN_PRIMARY_SIMULATION_SAMPLES  = 100;
  /**
   * When adding additional state, consider any necessary additions to {@link #tidyUp()}.
   */

  static
  {
    boolean lAssertionsEnabled = false;
    assert ((lAssertionsEnabled = true) == true);
    ASSERTIONS_ENABLED = lAssertionsEnabled;

    if (ASSERTIONS_ENABLED)
    {
      LOGGER.warn("WARNING: Assertions are enabled - this will impact performance");
    }
  }

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
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.netScore");
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
      int rawResult = (gameCharacteristics.numRoles == 1 ? result : ((result + winBonus) * 100) / 110);
      int normalizedResult = ((rawResult - MinRawNetScore) * 100) /
                             (MaxRawNetScore - MinRawNetScore);

      if (normalizedResult > 100 && !overExpectedRangeScoreReported)
      {
        normalizedResult = 100;
        overExpectedRangeScoreReported = true;
        LOGGER.warn("Saw score that nornmalized to > 100");
      }
      else if (normalizedResult < 0 && !underExpectedRangeScoreReported)
      {
        normalizedResult = 0;
        underExpectedRangeScoreReported = true;
        LOGGER.warn("Saw score that nornmalized to < 0");
      }

      return normalizedResult;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

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

  @Override
  public String getName()
  {
    return MachineSpecificConfiguration.getCfgVal(CfgItem.PLAYER_NAME, "Sancho 1.58n");
  }

  @Override
  public StateMachine getInitialStateMachine()
  {
    String lMatchID = getMatch().getMatchId();
    mLogName = lMatchID + "-" + getPort();
    ThreadContext.put("matchID", mLogName);

    ThreadControl.CPUIdParity = (getPort()%2 == 0);
    ThreadControl.reset();

    searchProcessor = new GameSearcher(transpositionTableSize, mLogName);

    if (!ThreadControl.RUN_SYNCHRONOUSLY)
    {
      Thread lSearchProcessorThread = new Thread(searchProcessor, "Search Processor");
      lSearchProcessorThread.setDaemon(true);
      lSearchProcessorThread.start();
    }

    //GamerLogger.setFileToDisplay("StateMachine");
    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                                                                      getMetaGamingTimeout());

    System.gc();

    currentMoveDepth = 0;

    return new StateMachineProxy(underlyingStateMachine, searchProcessor);
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

  @Override
  public void stateMachineMetaGame(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    if (ASSERTIONS_ENABLED)
    {
      LOGGER.warn("WARNING: Assertions are enabled - this will impact performance");
    }

    mSysStatsLogger = new SystemStatsLogger(mLogName);

    Random r = new Random();

    // If have been configured with a plan (for test purposes), load it now.
    // We'll still do everything else as normal, but whilst there are moves in
    // the plan, when it comes to play, we'll just play the specified move.
    plan = new GamePlan();
    if (planString != null)
    {
      plan.considerPlan(convertPlanString(planString));
    }

    puzzlePlayer = null;

    ourRole = getRole();
    LOGGER.info("We are: " + ourRole);
    numRoles = underlyingStateMachine.getRoles().size();
    roleOrdering = new RoleOrdering(underlyingStateMachine, ourRole);

    mTurn = 0;
    StatsLogUtils.Series.TURN.logDataPoint(System.currentTimeMillis(), mTurn);

    MinRawNetScore = 0;
    MaxRawNetScore = 100;
    underExpectedRangeScoreReported = false;
    overExpectedRangeScoreReported = false;

    int observedMinNetScore = Integer.MAX_VALUE;
    int observedMaxNetScore = Integer.MIN_VALUE;
    int simulationsPerformed = 0;
    int multiRoleSamples = 0;
    boolean greedyRolloutsDisabled = false;

    multiRoleAverageScoreDiff = 0;

    // Find latches.  This needs to be done before the AvailableGoalHeuristic is initialized.
    underlyingStateMachine.findLatches();

    CombinedHeuristic heuristic;

    if (MachineSpecificConfiguration.getCfgVal(CfgItem.DISABLE_PIECE_HEURISTIC, false))
    {
      heuristic = new CombinedHeuristic();
    }
    else
    {
      heuristic = new CombinedHeuristic(new PieceHeuristic() /*, new AvailableGoalHeuristic() */);
    }

    boolean hasHeuristicCandidates = heuristic.tuningInitialise(underlyingStateMachine, roleOrdering);

    ForwardDeadReckonInternalMachineState initialState = underlyingStateMachine.createInternalState(getCurrentState());

    gameCharacteristics = new RuntimeGameCharacteristics(numRoles);

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

    Collection<Factor> factors = underlyingStateMachine.getFactors();

    //	Perform a small number of move-by-move simulations to assess how
    //	the potential piece count heuristics behave at the granularity of
    //	a single decision
    long lMetaGameStartTime = System.currentTimeMillis();
    long lMetaGameStopTime = timeout - 5000;
    int numSamples = 0;

    // Spend half the time determining heuristic weights if there are any heuristics, else spend
    //  a short time just establishing the type of game
    long lHeuristicStopTime;

    if ( hasHeuristicCandidates )
    {
      lHeuristicStopTime = (lMetaGameStartTime + lMetaGameStopTime) / 2;
    }
    else
    {
      lHeuristicStopTime = lMetaGameStartTime + 2000;
    }

    //  Slight hack, but for now we don't bother continuing to simulate for a long time after discovering we're in
    //  a simultaneous turn game, because (for now anyway) we disable heuristics in such games anyway
    while (System.currentTimeMillis() < lHeuristicStopTime && (numSamples < MIN_PRIMARY_SIMULATION_SAMPLES || !gameCharacteristics.isSimultaneousMove))
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
            Factor turnFactor = null;

            for (Move move : legalMoves)
            {
              if ( factors != null )
              {
                for(Factor factor : factors)
                {
                  if ( factor.getMoves().contains(move))
                  {
                    if ( turnFactor != null && turnFactor != factor )
                    {
                      //underlyingStateMachine.disableFactorization();
                      //factors = null;
                      gameCharacteristics.moveChoicesFromMultipleFactors = true;
                      break;
                    }
                    turnFactor = factor;
                  }
                }
              }
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

        // Tell the heuristic about the interim state, for tuning purposes.
        heuristic.tuningInterimStateSample(sampleState, choosingRoleIndex);

        sampleState = underlyingStateMachine.getNextState(sampleState, jointMove);
      }

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));
      }

      // Tell the heuristic about the terminal state, for tuning purposes.
      assert(underlyingStateMachine.isTerminal(sampleState));
      heuristic.tuningTerminalStateSample(sampleState, roleScores);

      branchingFactorApproximation += (numBranchesTaken / numRoleMovesSimulated);
      numSamples++;
    }

    //  If we were able to run very few samples only don't make non-default
    //  assumptions about the game based on the inadequate sampling
    if ( numSamples < MIN_PRIMARY_SIMULATION_SAMPLES )
    {
      gameCharacteristics.isIteratedGame = false;
      heuristic.pruneAll();

      LOGGER.warn("Insufficient sampling time to reliably ascertain game characteristics");
    }
    else
    {
      branchingFactorApproximation /= numSamples;
    }

    //  For now we don't attempt heuristic usage in simultaneous move games
    if ( gameCharacteristics.isSimultaneousMove )
    {
      heuristic.pruneAll();
    }

    if (gameCharacteristics.isSimultaneousMove || gameCharacteristics.isPseudoSimultaneousMove)
    {
      if (!greedyRolloutsDisabled)
      {
        greedyRolloutsDisabled = true;
        underlyingStateMachine.disableGreedyRollouts();
      }
    }

    //  If we detected that moves from multiple factors are valid in the same turn
    //  then flag the factors as requiring the inclusion of a pseudo-noop as a valid
    //  search choice every move because we'll have to choose whether to play a move
    //  from one factor or another (imposing an artificial noop on the other)
    if (gameCharacteristics.moveChoicesFromMultipleFactors)
    {
      for (Factor factor : factors)
      {
        factor.setAlwaysIncludePseudoNoop(true);
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
                                                  null,
                                                  getRole(),
                                                  rolloutStats,
                                                  null,
                                                  null);

      int netScore = netScore(underlyingStateMachine, null);

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));

        if (i != 0 && gameCharacteristics.numRoles > 2)
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

      // Tell the heuristic about the termainal state, for tuning purposes.
      assert(underlyingStateMachine.isTerminal(finalState));
      heuristic.tuningTerminalStateSample(finalState, roleScores);

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

      //LOGGER.debug("Saw score of ", netScore);
      if (netScore < observedMinNetScore)
      {
        observedMinNetScore = netScore;
      }

      if (netScore > observedMaxNetScore)
      {
        observedMaxNetScore = netScore;
      }
    }

    // Complete heuristic tuning.
    heuristic.tuningComplete();

    LOGGER.info("branchingFactorApproximation = " + branchingFactorApproximation +
                ", averageBranchingFactor = " + averageBranchingFactor);
    //	Massive hack - assume that a game longer than 30 turns is not really an iterated game unless it's of fixed length
    if (gameCharacteristics.isIteratedGame &&
        (Math.abs(branchingFactorApproximation - averageBranchingFactor) > 0.1 || (maxNumTurns > 30 && maxNumTurns != minNumTurns)))
    {
      gameCharacteristics.isIteratedGame = false;
    }

    gameCharacteristics.setEarliestCompletionDepth(numRoles*minNumTurns);
    if ( maxNumTurns == minNumTurns )
    {
      gameCharacteristics.setIsFixedMoveCount();
    }

    //  Dump the game characteristics to trace output
    gameCharacteristics.report();

    double stdDevNumTurns = Math.sqrt(averageSquaredNumTurns -
                                      averageNumTurns * averageNumTurns);

    LOGGER.info("Range of lengths of sample games seen: [" +
                minNumTurns + "," + maxNumTurns + "], branching factor: " + averageBranchingFactor);
    LOGGER.info("Average num turns: " + averageNumTurns);
    LOGGER.info("Std deviation num turns: " + stdDevNumTurns);

    double explorationBias = 15 / (averageNumTurns + ((maxNumTurns + minNumTurns) / 2 - averageNumTurns) *
                                              stdDevNumTurns / averageNumTurns) + 0.4;
    if (explorationBias < 0.5)
    {
      explorationBias = 0.5;
    }
    else if (explorationBias > 1.2)
    {
      explorationBias = 1.2;
    }

    if (heuristic.includes(PieceHeuristic.class))
    {
      //	Empirically games with piece count heuristics seem to like lower
      //	exploration bias - not entirely sure why!
      explorationBias = explorationBias * 0.7;
    }

    gameCharacteristics.setExplorationBias(explorationBias);
    searchProcessor.setExplorationBiasRange(explorationBias * 0.8, explorationBias * 1.2);

    if (underlyingStateMachine.numRolloutDecisionNodeExpansions > 0)
    {
      LOGGER.info("Greedy rollout terminal discovery effectiveness: " +
                  (underlyingStateMachine.greedyRolloutEffectiveness * 100) /
                  underlyingStateMachine.numRolloutDecisionNodeExpansions);
      LOGGER.info("Num terminal props seen: " +
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

    LOGGER.info("Estimated greedy rollout cost: " + greedyRolloutCost);
    if (minNumTurns == maxNumTurns ||
        ((greedyRolloutCost > 8 || stdDevNumTurns < 0.15 * averageNumTurns || underlyingStateMachine.greedyRolloutEffectiveness < underlyingStateMachine.numRolloutDecisionNodeExpansions / 3) && gameCharacteristics.numRoles != 1))
    {
      if (!greedyRolloutsDisabled)
      {
        greedyRolloutsDisabled = true;
        underlyingStateMachine.disableGreedyRollouts();

        //	Scale up the estimate of simulation rate since we'll be running without the overhead
        //	of greedy rollouts (which is proportional to the branching factor)
        simulationsPerformed *= (1 + greedyRolloutCost);
      }
    }

    //	Special case handling for puzzles with hard-to-find wins
    //	WEAKEN THIS WHEN WE HAVE TRIAL A*
    if (gameCharacteristics.numRoles == 1 && observedMinNetScore == observedMaxNetScore &&
        observedMaxNetScore < 100 && factors == null )
    {
      //	8-puzzle type stuff
      LOGGER.info("Puzzle with no observed solution");

      MachineState terminalState;
      Set<MachineState> goalStates = underlyingStateMachine.findGoalStates(getRole(), 90, 100, 20);
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

        LOGGER.info("Found target state: " + terminalState);

        int targetStateSize = terminalState.getContents().size();

        if (targetStateSize < Math.max(2, initialState.size() / 2))
        {
          LOGGER.info("Unsuitable target state based on state elimination - ignoring");
        }
        else
        {
          puzzlePlayer = new TargetedSolutionStatePlayer(underlyingStateMachine, this, roleOrdering);
          puzzlePlayer.setTargetState(terminalState);
        }
      }
    }

    LOGGER.info("Min raw score = " + observedMinNetScore + ", max = " + observedMaxNetScore);
    LOGGER.info("multiRoleAverageScoreDiff = " + multiRoleAverageScoreDiff);

    if (observedMinNetScore == observedMaxNetScore)
    {
      observedMinNetScore = 0;
      observedMaxNetScore = 100;

      LOGGER.info("No score discrimination seen during simulation - resetting to [0,100]");
    }

    if (gameCharacteristics.numRoles == 1)
    {
      observedMinNetScore = 0;
      observedMaxNetScore = 100;

      LOGGER.info("Game is a puzzle so not normalizing scores");
    }

    //	Normalize score ranges
    MinRawNetScore = observedMinNetScore;
    MaxRawNetScore = observedMaxNetScore;
    multiRoleAverageScoreDiff = (multiRoleAverageScoreDiff * 100) /
                                (MaxRawNetScore - MinRawNetScore);

    int rolloutSampleSize;

    if (ThreadControl.ROLLOUT_THREADS == 0)
    {
      rolloutSampleSize = 1;
    }
    else
    {
      rolloutSampleSize = (int)(simulationsPerformed /
                                (5 * (simulationStopTime - simulationStartTime)) + 1);
      if (rolloutSampleSize > 100)
      {
        rolloutSampleSize = 100;
      }
    }

    gameCharacteristics.setRolloutSampleSize(rolloutSampleSize);
    LOGGER.info(simulationsPerformed *
                1000 /
                (simulationStopTime - simulationStartTime) +
                " simulations/second performed - setting rollout sample size to " + gameCharacteristics.getRolloutSampleSize());

    if (ProfilerContext.getContext() != null)
    {
      GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
    }

    if ((!gameCharacteristics.isIteratedGame || numRoles != 2) && puzzlePlayer == null)
    {
      if (ThreadControl.RUN_SYNCHRONOUSLY)
      {
        gameCharacteristics.setExplorationBias(explorationBias);
      }

      searchProcessor.setup(underlyingStateMachine,
                            initialState,
                            roleOrdering,
                            gameCharacteristics,
                            greedyRolloutsDisabled,
                            heuristic,
                            plan);
      searchProcessor.startSearch(System.currentTimeMillis() + 60000,
                                  new ForwardDeadReckonInternalMachineState(initialState),
                                  (short)0);
      try
      {
        Thread.sleep(Math.max(timeout - 3000 - System.currentTimeMillis(), 0));
      }
      catch (InterruptedException lEx)
      {
        LOGGER.error("Unexpected interruption during meta-gaming", lEx);
      }
    }
  }

  @Override
  public Move stateMachineSelectMove(long timeout)
    throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
    // We get the current start time
    long start = System.currentTimeMillis();
    long finishBy = timeout - 2500;
    Move bestMove;
    List<Move> moves;

    mTurn++;
    LOGGER.info("Starting turn " + mTurn);
    StatsLogUtils.Series.TURN.logDataPoint(start, mTurn);

    if (ProfilerContext.getContext() != null)
    {
      ProfilerContext.getContext().resetStats();
    }

    ForwardDeadReckonInternalMachineState currentState;

    searchProcessor.requestYield(true);

    LOGGER.debug("Calculating current state");

    synchronized (searchProcessor.getSerializationObject())
    {
      currentState = underlyingStateMachine.createInternalState(getCurrentState());
      moves = underlyingStateMachine.getLegalMoves(currentState, ourRole);

      //LOGGER.warn("Received current state: " + getCurrentState());
      //LOGGER.warn("Using current state: " + currentState);
      //LOGGER.warn("Legal moves: " + moves);

      if (underlyingStateMachine.isTerminal(currentState))
      {
        LOGGER.warn("Asked to search in terminal state!");
      }
    }

    LOGGER.debug("Setting search root");

    if ((plan != null) && (!plan.isEmpty()))
    {
      // We have a pre-prepared plan.  Simply play the next move.
      bestMove = plan.nextMove();
      LOGGER.info("Playing pre-planned move: " + bestMove);

      //  We need to keep the search 'up with' the plan to make forced-play
      //  testing work properly, or else the search will not be 'primed'
      //  during forced play when the plan runs out
      searchProcessor.startSearch(finishBy, currentState, currentMoveDepth);
      currentMoveDepth += numRoles;
    }
    else if (gameCharacteristics.isIteratedGame && numRoles == 2)
    {
      IteratedGamePlayer iteratedPlayer = new IteratedGamePlayer(underlyingStateMachine, this, gameCharacteristics.isPseudoSimultaneousMove, roleOrdering, gameCharacteristics.competitivenessBonus);
      bestMove = iteratedPlayer.selectMove(moves, timeout);
      LOGGER.info("Playing best iterated game move: " + bestMove);
    }
    else if (puzzlePlayer != null)
    {
      //bestMove = selectAStarMove(moves, timeout);
      bestMove = puzzlePlayer.selectMove(moves, timeout);
      LOGGER.info("Playing best puzzle move: " + bestMove);
    }
    else
    {
      //emptyTree();
      //root = null;
      //validateAll();
      searchProcessor.startSearch(finishBy, currentState, currentMoveDepth);
      currentMoveDepth += numRoles;

      searchProcessor.requestYield(false);

      LOGGER.debug("Waiting for processing");

      try
      {
        while (System.currentTimeMillis() < finishBy && !searchProcessor.isComplete())
        {
          if (ThreadControl.RUN_SYNCHRONOUSLY)
          {
            searchProcessor.expandSearch(true);
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
      if ( searchProcessor.isComplete() )
      {
        LOGGER.info("Complete root");
      }

      LOGGER.debug("Time to submit order - ask GameSearcher to yield");
      searchProcessor.requestYield(true);

      //validateAll();
      long getBestMoveStartTime = System.currentTimeMillis();
      bestMove = searchProcessor.getBestMove();
      if ( System.currentTimeMillis() - getBestMoveStartTime > 250 )
      {
        LOGGER.warn("Retrieveing the best move took " + (System.currentTimeMillis() - getBestMoveStartTime) + "ms");
      }

      if (!moves.contains(bestMove))
      {
        LOGGER.warn("Selected illegal move!!");
        bestMove = moves.get(0);
      }
      LOGGER.info("Playing move: " + bestMove);

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

    LOGGER.debug("Move took: " + (stop - start));

    if (bestMove == null)
    {
      LOGGER.error("NO MOVE FOUND!");
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

  @Override
  public void stateMachineStop()
  {
    // Log the final score.

    synchronized (searchProcessor.getSerializationObject())
    {
      ForwardDeadReckonInternalMachineState lState = underlyingStateMachine.createInternalState(getCurrentState());
      int lFinalScore = underlyingStateMachine.getGoal(lState, ourRole);
      StatsLogUtils.Series.SCORE.logDataPoint(lFinalScore);
    }

    tidyUp();
  }

  @Override
  public void stateMachineAbort()
  {
    tidyUp();
  }

  /**
   * Tidy up game state at the end of the game.
   */
  private void tidyUp()
  {
    StatsLogUtils.Series.TURN.logDataPoint(System.currentTimeMillis(), 999);

    // Terminate all other threads.
    if (searchProcessor != null)
    {
      searchProcessor.terminate();
      searchProcessor = null;
    }

    if (mSysStatsLogger != null)
    {
      mSysStatsLogger.stop();
      mSysStatsLogger = null;
    }

    // Free off all our references.
    ourRole                      = null;
    planString                   = null;
    plan                         = null;
    roleOrdering                 = null;
    canonicallyOrderedMoveBuffer = null;
    underlyingStateMachine       = null;
    puzzlePlayer                 = null;

    // Get our parent to tidy up too.
    cleanupAfterMatch();

    // Prompt the JVM to do garbage collection, because we've hopefully just freed a lot of stuff.
    long endGCTime = System.currentTimeMillis() + 5000;
    for (int ii = 0; ii < 1000 && System.currentTimeMillis() < endGCTime; ii++)
    {
      System.gc();
      try {Thread.sleep(1);} catch (InterruptedException lEx) {/* Whatever */}
    }
  }
}
