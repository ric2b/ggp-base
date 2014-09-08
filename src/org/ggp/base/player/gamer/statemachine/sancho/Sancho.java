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
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.GoalsStabilityHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.MajorityGoalsHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.PieceHeuristic;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * The Sancho General Game Player.
 */
public class Sancho extends SampleGamer
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final long SAFETY_MARGIN = MachineSpecificConfiguration.getCfgVal(CfgItem.SAFETY_MARGIN, 2500);

  private static final int MIN_PRIMARY_SIMULATION_SAMPLES = 100;

  // Determine whether assertions are enabled for the JVM.
  private static final boolean ASSERTIONS_ENABLED;
  static
  {
    boolean lAssertionsEnabled = false;
    assert ((lAssertionsEnabled = true) == true);
    ASSERTIONS_ENABLED = lAssertionsEnabled;

    if (ASSERTIONS_ENABLED)
    {
      System.err.println("WARNING: Assertions are enabled - this will impact performance");
    }
  }

  /**
   * When adding additional state, consider any necessary additions to {@link #tidyUp()}.
   */
  public Role                         ourRole;
  private int                         mTurn                           = 0;
  private String                      planString                      = null;
  private GamePlan                    plan                            = null;
  private int                         transpositionTableSize          = MachineSpecificConfiguration.getCfgVal(CfgItem.NODE_TABLE_SIZE, 2000000);
  private RoleOrdering                roleOrdering                    = null;
  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine = null;
  private StateMachineProxy           stateMachineProxy               = null;
  private int                         numRoles                        = 0;
  private int                         MinRawNetScore                  = 0;
  private int                         MaxRawNetScore                  = 100;
  private int                         multiRoleAverageScoreDiff       = 0;
  private short                       currentMoveDepth                = 0;
  private boolean                     underExpectedRangeScoreReported = false;
  private boolean                     overExpectedRangeScoreReported  = false;
  private GameSearcher                searchProcessor                 = null;
  private String                      mLogName                        = null;
  private SystemStatsLogger           mSysStatsLogger                 = null;
  /**
   * When adding additional state, consider any necessary additions to {@link #tidyUp()}.
   */

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

  private int netScore(ForwardDeadReckonPropnetStateMachine stateMachine,
                       ForwardDeadReckonInternalMachineState state)
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
      int rawResult = (mGameCharacteristics.numRoles == 1 ? result : ((result + winBonus) * 100) / 110);
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

  @Override
  public String getName()
  {
    return MachineSpecificConfiguration.getCfgVal(CfgItem.PLAYER_NAME, "Sancho 1.60h");
  }

  @Override
  public StateMachine getInitialStateMachine()
  {
    String lMatchID = getMatch().getMatchId();
    mLogName = lMatchID + "-" + getPort();
    ThreadContext.put("matchID", mLogName);

    ThreadControl.CPUIdParity = (getPort()%2 == 0);
    ThreadControl.reset();

    //GamerLogger.setFileToDisplay("StateMachine");
    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                                                                      getMetaGamingTimeout(),
                                                                      getRole(),
                                                                      mGameCharacteristics);

    System.gc();

    currentMoveDepth = 0;

    stateMachineProxy = new StateMachineProxy(underlyingStateMachine);
    return stateMachineProxy;
  }

  private static int unNormalizedStateDistance(MachineState queriedState, MachineState targetState)
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
     throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    MachineSpecificConfiguration.logConfig();
    if (ASSERTIONS_ENABLED)
    {
      LOGGER.warn("WARNING: Assertions are enabled - this will impact performance");
    }

    mSysStatsLogger = new SystemStatsLogger(mLogName);

    searchProcessor = new GameSearcher(transpositionTableSize, underlyingStateMachine.getRoles().length, mLogName);
    stateMachineProxy.setController(searchProcessor);

    if (!ThreadControl.RUN_SYNCHRONOUSLY)
    {
      Thread lSearchProcessorThread = new Thread(searchProcessor, "Search Processor");
      lSearchProcessorThread.setDaemon(true);
      lSearchProcessorThread.start();
    }

    Random r = new Random();

    // If have been configured with a plan (for test purposes), load it now.
    // We'll still do everything else as normal, but whilst there are moves in
    // the plan, when it comes to play, we'll just play the specified move.
    plan = new GamePlan();
    if (planString != null)
    {
      plan.considerPlan(convertPlanString(planString));
    }

    ourRole = getRole();
    LOGGER.info("Start clock: " + getMatch().getStartClock() + "s");
    LOGGER.info("Play clock:  " + getMatch().getPlayClock() + "s");
    LOGGER.info("We are:      " + ourRole);

    numRoles = underlyingStateMachine.getRoles().length;
    roleOrdering = new RoleOrdering(underlyingStateMachine, ourRole);

    underlyingStateMachine.setRoleOrdering(roleOrdering);

    mTurn = 0;
    StatsLogUtils.Series.TURN.logDataPoint(System.currentTimeMillis(), mTurn);

    MinRawNetScore = 0;
    MaxRawNetScore = 100;
    underExpectedRangeScoreReported = false;
    overExpectedRangeScoreReported = false;

    int observedMinNetScore = Integer.MAX_VALUE;
    int observedMaxNetScore = Integer.MIN_VALUE;
    long simulationsPerformed = 0;
    int multiRoleSamples = 0;
    boolean greedyRolloutsDisabled = MachineSpecificConfiguration.getCfgVal(CfgItem.DISABLE_GREEDY_ROLLOUTS, false);

    multiRoleAverageScoreDiff = 0;

    // Set up those game characteristics that we only know now that we've got a state machine.
    mGameCharacteristics.numRoles = numRoles;

    // Check if we already know how to solve this game.
    String lSavedPlan = mGameCharacteristics.getPlan();
    if (lSavedPlan != null)
    {
      // We've played this game before and know how to solve it.
      LOGGER.info("Considering saved plan: " + lSavedPlan);
      plan.considerPlan(convertPlanString(mGameCharacteristics.getPlan()));
      LOGGER.info("Ready to play");
      return;
    }

    // Analyze game semantics.  This includes latch identification and search filter generation.
    // This needs to be done before the AvailableGoalHeuristic is initialized.
    underlyingStateMachine.performSemanticAnalysis();

    CombinedHeuristic heuristic;

    MajorityGoalsHeuristic goalsPredictionHeuristic = new MajorityGoalsHeuristic();
    GoalsStabilityHeuristic goalsStabilityHeuristic = new GoalsStabilityHeuristic();

    if (MachineSpecificConfiguration.getCfgVal(CfgItem.DISABLE_PIECE_HEURISTIC, false))
    {
      heuristic = new CombinedHeuristic(goalsPredictionHeuristic, goalsStabilityHeuristic);
    }
    else
    {
      heuristic = new CombinedHeuristic(new PieceHeuristic(), goalsPredictionHeuristic, goalsStabilityHeuristic /*, new AvailableGoalHeuristic() */);
    }

    boolean hasHeuristicCandidates = heuristic.tuningInitialise(underlyingStateMachine, roleOrdering);

    ForwardDeadReckonInternalMachineState initialState = underlyingStateMachine.createInternalState(getCurrentState());

    //	Sample to see if multiple roles have multiple moves available
    //	implying this must be a simultaneous move game
    //	HACK - actually only count games where both players can play the
    //	SAME move - this gets blocker but doesn't include fully factored
    //	games like C4-simultaneous or Chinook (but it's a hack!)
    mGameCharacteristics.isSimultaneousMove = false;
    mGameCharacteristics.isPseudoSimultaneousMove = false;
    mGameCharacteristics.isPseudoPuzzle = underlyingStateMachine.getIsPseudoPuzzle();

    //  Create masks of possible control props, which we'll whittle down during simulation
    //  If we wind up with a unique prop for each role e'll note it for future use
    ForwardDeadReckonInternalMachineState[] roleControlMasks = new ForwardDeadReckonInternalMachineState[numRoles];
    ForwardDeadReckonPropositionInfo[] roleControlProps = new ForwardDeadReckonPropositionInfo[numRoles];

    for(int i = 0; i < numRoles; i++)
    {
      roleControlMasks[i] = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
      roleControlMasks[i].clear();
      roleControlMasks[i].invert();
    }

    //	Also monitor whether any given player always has the SAME choice of move (or just a single choice)
    //	every turn - such games are (highly probably) iterated games
    List<Set<Move>> roleMoves = new ArrayList<>();
    mGameCharacteristics.isIteratedGame = true;

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

    //  Buffer for new states
    ForwardDeadReckonInternalMachineState newState = new ForwardDeadReckonInternalMachineState(initialState);

    //  Slight hack, but for now we don't bother continuing to simulate for a long time after discovering we're in
    //  a simultaneous turn game, because (for now anyway) we disable heuristics in such games anyway
    while (System.currentTimeMillis() < lHeuristicStopTime && (numSamples < MIN_PRIMARY_SIMULATION_SAMPLES || !mGameCharacteristics.isSimultaneousMove))
    {
      ForwardDeadReckonInternalMachineState sampleState = new ForwardDeadReckonInternalMachineState(initialState);

      int numRoleMovesSimulated = 0;
      int numBranchesTaken = 0;

      heuristic.tuningStartSampleGame();

      while (!underlyingStateMachine.isTerminal(sampleState))
      {
        boolean roleWithChoiceSeen = false;
        ForwardDeadReckonLegalMoveInfo[] jointMove = new ForwardDeadReckonLegalMoveInfo[numRoles];
        Set<Move> allMovesInState = new HashSet<>();

        int choosingRoleIndex = -2;
        for (int i = 0; i < numRoles; i++)
        {
          //List<Move> legalMoves = underlyingStateMachine.getLegalMovesCopy(sampleState,
          //                                                                 roleOrdering.roleIndexToRole(i));
          List<ForwardDeadReckonLegalMoveInfo> legalMoves = new ArrayList<>(underlyingStateMachine.getLegalMoves(sampleState, roleOrdering.roleIndexToRole(i)));

          if (legalMoves.size() > 1)
          {
            //  This player has control (may not be only this player)
            roleControlMasks[i].intersect(sampleState);

            Set<Move> previousChoices = roleMoves.get(i);

            if (previousChoices != null)
            {
              if ( mGameCharacteristics.isIteratedGame )
              {
                Set<Move> moves = new HashSet<>();

                for(ForwardDeadReckonLegalMoveInfo moveInfo : legalMoves)
                {
                  moves.add(moveInfo.move);
                }

                if ( !previousChoices.equals(moves) )
                {
                  mGameCharacteristics.isIteratedGame = false;
                }
              }
            }
            else
            {
              Set<Move> moves = new HashSet<>();

              for(ForwardDeadReckonLegalMoveInfo moveInfo : legalMoves)
              {
                moves.add(moveInfo.move);
              }

              roleMoves.set(i, moves);
            }

            choosingRoleIndex = i;
            Factor turnFactor = null;

            for (ForwardDeadReckonLegalMoveInfo moveInfo : legalMoves)
            {
              if ( factors != null )
              {
                for(Factor factor : factors)
                {
                  if ( factor.getMoves().contains(moveInfo.move))
                  {
                    if ( turnFactor != null && turnFactor != factor )
                    {
                      mGameCharacteristics.moveChoicesFromMultipleFactors = true;
                      break;
                    }
                    turnFactor = factor;
                  }
                }
              }
              if (allMovesInState.contains(moveInfo.move))
              {
                mGameCharacteristics.isSimultaneousMove = true;
                choosingRoleIndex = -1;
                break;
              }
              allMovesInState.add(moveInfo.move);
            }

            if (roleWithChoiceSeen)
            {
              mGameCharacteristics.isPseudoSimultaneousMove = true;
              choosingRoleIndex = -1;
            }

            roleWithChoiceSeen = true;

            numBranchesTaken += legalMoves.size();
            numRoleMovesSimulated++;
          }
          jointMove[i] = legalMoves.get(r.nextInt(legalMoves.size()));
        }

        heuristic.tuningInterimStateSample(sampleState, choosingRoleIndex);

        underlyingStateMachine.getNextState(sampleState, null, jointMove, newState);

        sampleState.copy(newState);
      }

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));
      }

      // Tell the heuristic about the terminal state, for tuning purposes.
      assert(underlyingStateMachine.isTerminal(sampleState));
      heuristic.tuningTerminalStateSample(sampleState, roleScores);

      if (numRoleMovesSimulated > 0)
      {
        branchingFactorApproximation += (numBranchesTaken / numRoleMovesSimulated);
      }
      numSamples++;
    }

    // Complete heuristic tuning.
    heuristic.tuningComplete();

    //  If we were able to run very few samples only don't make non-default
    //  assumptions about the game based on the inadequate sampling
    if ( numSamples < MIN_PRIMARY_SIMULATION_SAMPLES )
    {
      mGameCharacteristics.isIteratedGame = false;
      heuristic.pruneAll();

      LOGGER.warn("Insufficient sampling time to reliably ascertain game characteristics");
    }
    else
    {
      branchingFactorApproximation /= numSamples;
    }

    //  For now we don't attempt heuristic usage in simultaneous move games
    if ( mGameCharacteristics.isSimultaneousMove )
    {
      heuristic.pruneAll();
    }

    mGameCharacteristics.setGoalsStability(goalsStabilityHeuristic.getGoalStability());

    if (mGameCharacteristics.isSimultaneousMove || mGameCharacteristics.isPseudoSimultaneousMove)
    {
      if (!greedyRolloutsDisabled)
      {
        greedyRolloutsDisabled = true;
        underlyingStateMachine.disableGreedyRollouts();
      }
    }
    else if ( numRoles == 1 )
    {
      roleControlProps = null;
    }
    else
    {
      //  Did we identify unique control props?
      //  We look for a proposition that is always true when a given role has more than one
      //  move choice, and is never true if any other player has more than one move choice
      //  This is slightly ovr-specific, and will not work for games with control denoted by a
      //  set of props rather than a single one (e.g. - Pentago), but it will suffice for now
      ForwardDeadReckonInternalMachineState[] inverseMasks = new ForwardDeadReckonInternalMachineState[numRoles];

      for(int i = 0; i < numRoles; i++)
      {
        inverseMasks[i] = new ForwardDeadReckonInternalMachineState(roleControlMasks[i]);
        inverseMasks[i].invert();
      }

      for(int i = 0; i < numRoles; i++)
      {
        //  Eliminate anything common to multiple roles
        for(int j = 0; j < numRoles; j++)
        {
          if ( j != i )
          {
            roleControlMasks[i].intersect(inverseMasks[j]);
          }
        }
        if ( roleControlMasks[i].size() != 1 )
        {
          LOGGER.info("Non-unique control mask for role " + i + ": " + roleControlMasks[i]);

          roleControlProps = null;
          break;
        }

        roleControlProps[i] = roleControlMasks[i].resolveIndex(roleControlMasks[i].getContents().nextSetBit(0));
      }

      if ( roleControlProps != null )
      {
        for(int i = 0; i < numRoles; i++)
        {
          LOGGER.info("Role " + i + " has control prop: " + roleControlProps[i].sentence);
        }
      }
    }

    //  If we detected that moves from multiple factors are valid in the same turn
    //  then flag the factors as requiring the inclusion of a pseudo-noop as a valid
    //  search choice every move because we'll have to choose whether to play a move
    //  from one factor or another (imposing an artificial noop on the other)
    if (mGameCharacteristics.moveChoicesFromMultipleFactors)
    {
      assert(factors != null);
      for (Factor factor : factors)
      {
        factor.setAlwaysIncludePseudoNoop(true);
      }
    }

    //	Simulate and derive a few basic stats:
    //	1) Is the game a puzzle?
    //	2) For each role what is the largest and the smallest score that seem reachable and what are the corresponding net scores
    long simulationStartTime = System.currentTimeMillis();
    //  Always do this for at least a second even if we technically don't have time to do so, since not running it
    //  at all causes all sorts of problems
    long simulationStopTime = Math.min(Math.max(timeout - (mGameCharacteristics.numRoles == 1 ? 10000 : 5000), simulationStartTime + 1000),
                                       simulationStartTime + 10000);

    int[] rolloutStats = new int[2];
    int maxNumTurns = 0;
    int minNumTurns = Integer.MAX_VALUE;
    double averageBranchingFactor = 0;
    double averageNumTurns = 0;
    double averageSquaredNumTurns = 0;
    double averageNumNonDrawTurns = 0;
    int numNonDrawSimulations = 0;
    int numMaxLengthDraws = 0;
    int numMaxLengthGames = 0;

    while (System.currentTimeMillis() < simulationStopTime)
    {
      simulationsPerformed++;

      underlyingStateMachine.getDepthChargeResult(initialState,
                                                  null,
                                                  getRole(),
                                                  rolloutStats,
                                                  null,
                                                  null,
                                                  1000);

      int netScore = netScore(underlyingStateMachine, null);
      if ( netScore != 50 )
      {
        numNonDrawSimulations++;
        averageNumNonDrawTurns += rolloutStats[0];
      }

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));

        if (i != 0 && mGameCharacteristics.numRoles > 2)
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

        numMaxLengthGames = 0;
      }

      if ( rolloutStats[0] == maxNumTurns)
      {
        numMaxLengthGames++;

        if ( netScore == 50 )
        {
          numMaxLengthDraws++;
        }
      }

      averageBranchingFactor = (averageBranchingFactor *
                                (simulationsPerformed - 1) + rolloutStats[1]) /
                               simulationsPerformed;
      mGameCharacteristics.getChoicesHighWaterMark(rolloutStats[1]);

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

    if ( numNonDrawSimulations != 0 )
    {
      averageNumNonDrawTurns /= numNonDrawSimulations;
    }

    LOGGER.info("branchingFactorApproximation = " + branchingFactorApproximation +
                ", averageBranchingFactor = " + averageBranchingFactor +
                ", choices high water mark = " + mGameCharacteristics.getChoicesHighWaterMark(0));
    //	Massive hack - assume that a game longer than 30 turns is not really an iterated game unless it's of fixed length
    if (mGameCharacteristics.isIteratedGame &&
        (Math.abs(branchingFactorApproximation - averageBranchingFactor) > 0.1 || (maxNumTurns > 30 && maxNumTurns != minNumTurns)))
    {
      mGameCharacteristics.isIteratedGame = false;
    }

    double stdDevNumTurns = Math.sqrt(averageSquaredNumTurns -
                                      averageNumTurns * averageNumTurns);

    mGameCharacteristics.setMaxLength(maxNumTurns);
    mGameCharacteristics.setMinLength(minNumTurns);
    mGameCharacteristics.setAverageLength(averageNumTurns);
    mGameCharacteristics.setStdDeviationLength(stdDevNumTurns);
    mGameCharacteristics.setAverageNonDrawLength(averageNumNonDrawTurns);
    mGameCharacteristics.setMaxGameLengthDrawsProportion(((double)numMaxLengthDraws)/(double)numMaxLengthGames);

    mGameCharacteristics.setEarliestCompletionDepth(numRoles*minNumTurns);
    if ( maxNumTurns == minNumTurns )
    {
      mGameCharacteristics.setIsFixedMoveCount();
    }

    //  Dump the game characteristics to trace output
    mGameCharacteristics.report();

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

    mGameCharacteristics.setExplorationBias(explorationBias);
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
        ((greedyRolloutCost > 8 || stdDevNumTurns < 0.15 * averageNumTurns || underlyingStateMachine.greedyRolloutEffectiveness < underlyingStateMachine.numRolloutDecisionNodeExpansions / 3) &&
         mGameCharacteristics.numRoles != 1 &&
         !underlyingStateMachine.hasNegativelyLatchedGoals() &&
         !underlyingStateMachine.hasPositivelyLatchedGoals()))
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
    if (mGameCharacteristics.numRoles == 1 && observedMinNetScore == observedMaxNetScore &&
        observedMaxNetScore < 100 && factors == null )
    {
      //	8-puzzle type stuff
      LOGGER.info("Puzzle with no observed solution");

      MachineState terminalState;
      Set<MachineState> goalStates = underlyingStateMachine.findGoalStates(getRole(), 90, 500, 20);
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
          Collection<Move> solution = new TargetedSolutionStatePlayer(underlyingStateMachine,
                                                                      terminalState,
                                                                      this).attemptAStarSolve(99,
                                                                                              timeout - SAFETY_MARGIN);
          if (solution != null)
          {
            plan.considerPlan(solution);
            LOGGER.info("Solved by A*");
          }
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

    if (mGameCharacteristics.numRoles == 1)
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
                                (40 * (simulationStopTime - simulationStartTime)) + 1);
      if (rolloutSampleSize > 100)
      {
        rolloutSampleSize = 100;
      }
    }

    mGameCharacteristics.setRolloutSampleSize(rolloutSampleSize);
    LOGGER.info("Performed " + simulationsPerformed + " simulations in " + (simulationStopTime - simulationStartTime) + "ms");
    LOGGER.info(simulationsPerformed *
                1000 /
                (simulationStopTime - simulationStartTime) +
                " simulations/second performed - setting rollout sample size to " + mGameCharacteristics.getRolloutSampleSize());

    if (ProfilerContext.getContext() != null)
    {
      GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
    }

    if (!mGameCharacteristics.isIteratedGame || numRoles != 2)
    {
      if (ThreadControl.RUN_SYNCHRONOUSLY)
      {
        mGameCharacteristics.setExplorationBias(explorationBias);
      }

      searchProcessor.setup(underlyingStateMachine,
                            initialState,
                            roleOrdering,
                            mGameCharacteristics,
                            greedyRolloutsDisabled,
                            heuristic,
                            plan,
                            roleControlProps);
      searchProcessor.startSearch(System.currentTimeMillis() + 60000,
                                  new ForwardDeadReckonInternalMachineState(initialState),
                                  (short)0);

      searchUntil(timeout - SAFETY_MARGIN);
    }

    LOGGER.info("Ready to play");
  }

  @Override
  public Move stateMachineSelectMove(long timeout)
    throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
    // We get the current start time
    long start = System.currentTimeMillis();
    long finishBy = timeout - SAFETY_MARGIN;
    Move bestMove;
    List<Move> moves;

    LOGGER.info("Moves played for turn " + mTurn + ": " + getMatch().getMostRecentMoves());

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
      moves = underlyingStateMachine.getLegalMovesCopy(currentState, ourRole);

      //LOGGER.warn("Received current state: " + getCurrentState());
      //LOGGER.warn("Using current state: " + currentState);
      //LOGGER.warn("Legal moves: " + moves);

      if (underlyingStateMachine.isTerminal(currentState))
      {
        LOGGER.warn("Asked to search in terminal state!");
        assert(false);
      }
    }

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
    else if (mGameCharacteristics.isIteratedGame && numRoles == 2)
    {
      IteratedGamePlayer iteratedPlayer = new IteratedGamePlayer(underlyingStateMachine, this, mGameCharacteristics.isPseudoSimultaneousMove, roleOrdering, mGameCharacteristics.competitivenessBonus);
      bestMove = iteratedPlayer.selectMove(moves, timeout);
      LOGGER.info("Playing best iterated game move: " + bestMove);
    }
    else
    {
      //emptyTree();
      //root = null;
      //validateAll();
      LOGGER.debug("Setting search root");
      searchProcessor.startSearch(finishBy, currentState, currentMoveDepth);
      currentMoveDepth += numRoles;

      searchProcessor.requestYield(false);

      LOGGER.debug("Waiting for processing");
      searchUntil(finishBy);

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
        assert(false);
        bestMove = moves.get(0);
      }
      LOGGER.info("Playing move: " + bestMove);

      // Record that we've made the move.  Until we've heard back from the server, the game searcher will always search
      // down this branch.  (This must be done before we release the game searcher again.)
      searchProcessor.chooseMove(bestMove);

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

  private void searchUntil(long xiFinishBy)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    try
    {
      while (System.currentTimeMillis() < xiFinishBy && !searchProcessor.isComplete())
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
    catch (InterruptedException lEx)
    {
      LOGGER.error("Unexpected interruption during meta-gaming", lEx);
    }

    if (searchProcessor.isComplete())
    {
      LOGGER.info("Early search termination because root is complete");
    }
  }

  @Override
  public void stateMachineStop()
  {
    // Log the final score.
    int lFinalScore;
    synchronized (searchProcessor.getSerializationObject())
    {
      ForwardDeadReckonInternalMachineState lState = underlyingStateMachine.createInternalState(getCurrentState());
      lFinalScore = underlyingStateMachine.getGoal(lState, ourRole);
      LOGGER.info("Final score: " + lFinalScore);
      StatsLogUtils.Series.SCORE.logDataPoint(lFinalScore);
      StatsLogUtils.Series.SAMPLE_RATE.logDataPoint(mGameCharacteristics.getRolloutSampleSize());
    }

    // If we've just solved a puzzle for the first time, save the game history as a plan.
    if ((lFinalScore == 100) &&
        (mGameCharacteristics.numRoles == 1) &&
        (mGameCharacteristics.getPlan() == null))
    {
      mGameCharacteristics.setPlan(convertHistoryToPlan());
    }

    tidyUp();
  }

  @Override
  public void stateMachineAbort()
  {
    LOGGER.warn("Game aborted by server");
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

    // Save anything that we've learned about this game.
    mGameCharacteristics.saveConfig();

    // Free off all our references.
    ourRole                      = null;
    planString                   = null;
    plan                         = null;
    roleOrdering                 = null;
    underlyingStateMachine       = null;

    // Get our parent to tidy up too.
    cleanupAfterMatch();

    // Prompt the JVM to do garbage collection, because we've hopefully just freed a lot of stuff.
    long endGCTime = System.currentTimeMillis() + 3000;
    for (int ii = 0; ii < 1000 && System.currentTimeMillis() < endGCTime; ii++)
    {
      System.gc();
      try {Thread.sleep(1);} catch (InterruptedException lEx) {/* Whatever */}
    }

    LOGGER.info("Tidy-up complete");
  }
}
