package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.player.gamer.statemachine.sample.SampleGamer;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.PayoffMatrixGamePlayer.UnsupportedGameException;
import org.ggp.base.player.gamer.statemachine.sancho.Watchdog.WatchdogExpiryHandler;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.CombinedHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.GoalsStabilityHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.MajorityGoalsHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.PieceHeuristic;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
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
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine.PlayoutInfo;
import org.ggp.base.util.statemachine.playoutPolicy.PlayoutPolicyGoalGreedyWithPop;
import org.ggp.base.util.symbol.grammar.SymbolPool;

import com.google.common.io.CharStreams;

/**
 * The Sancho General Game Player.
 */
public class Sancho extends SampleGamer implements WatchdogExpiryHandler
{
  private static final Logger LOGGER = LogManager.getLogger();
  private static final long SAFETY_MARGIN = MachineSpecificConfiguration.getCfgInt(CfgItem.SAFETY_MARGIN);
  private static final int MIN_PRIMARY_SIMULATION_SAMPLES = 100;

  // Do one-off start-of-day processing.
  static
  {
    StdOutErrLog.tieSystemOutAndErrToLog();
  }
  private static final String LAST_COMMIT = getWorkingCopyRevision();
  private static final boolean ASSERTIONS_ENABLED = areAssertionsEnabled();

  /**
   * When adding additional state, you MUST null out references in {@link #tidyUp()}.
   */
  private Role                        ourRole;
  private int                         mTurn                           = 0;
  private String                      mPlanString                     = null;
  private GamePlan                    mPlan                           = null;
  private int                         transpositionTableSize          = MachineSpecificConfiguration.getCfgInt(CfgItem.NODE_TABLE_SIZE);
  private RoleOrdering                roleOrdering                    = null;
  private ForwardDeadReckonPropositionInfo[] roleControlProps         = null;
  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine = null;
  private ForwardDeadReckonInternalMachineState previousTurnRootState = null;
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
  private Move                        mLastMove                       = null;
  private ForwardDeadReckonLegalMoveInfo lastMove                     = null;
  private int                         mFinalScore                     = -1;
  private boolean                     mSolvedFromStart                = false;
  private Tlkio                       mBroadcaster                    = null;
  private Watchdog                    mWatchdog                       = null;
  /**
   * When adding additional state, you MUST null out references in {@link #tidyUp()}.
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
    mPlanString = xiParam.substring(5);

    // Start pre-warming he search tree 2 moves before he end of the plan so that we arrive at the turn we need to
    // search in earnest in with a warmed-up tree.  (This isn't needed in the case of a learned plan, because they
    // always take us to the end of the game.)
    GameSearcher.thinkBelowPlanSize = 2;
  }

  private int netScore(ForwardDeadReckonPropnetStateMachine stateMachine,
                       ForwardDeadReckonInternalMachineState state)
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

  @Override
  public String getName()
  {
    return MachineSpecificConfiguration.getCfgStr(CfgItem.PLAYER_NAME);
  }

  @Override
  public StateMachine getInitialStateMachine()
  {
    String lMatchID = getMatch().getMatchId();
    mLogName = lMatchID + "-" + getPort();
    ThreadContext.put("matchID", mLogName);

    // If we still have a watchdog from a previous game, it must be running as a zombie.  (The framework prevents us
    // from playing a new game if it thinks we're still running an old one.)  Immediately tidy-up the old match.
    if (mWatchdog != null)
    {
      LOGGER.error("Watchdog exists at start of new match");
      tidyUp(false, true);
    }

    mWatchdog = new Watchdog(MachineSpecificConfiguration.getCfgInt(CfgItem.DEAD_MATCH_INTERVAL), this);

    ThreadControl.sCPUIdParity = (getPort()%2 == 0);
    ThreadControl.reset();

    underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS + 1,
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

  /**
   * @return the commit ID of the latest revision in the working copy.
   */
  private static String getWorkingCopyRevision()
  {
    String lLastCommit = "<Unknown>";

    String lWCPath = MachineSpecificConfiguration.getCfgStr(CfgItem.WC_LOCATION);
    if (lWCPath != null)
    {
      try
      {
        File lWCRoot = new File(lWCPath);
        InputStream lInput = Runtime.getRuntime().exec("git rev-parse HEAD", null, lWCRoot).getInputStream();
        lLastCommit = CharStreams.toString(new InputStreamReader(lInput));
        lLastCommit = lLastCommit.replace("\r", "").replace("\n", "");
      }
      catch (IOException lEx)
      {
        LOGGER.warn("Failed to get git version", lEx);
      }
    }

    return lLastCommit;
  }

  private static boolean areAssertionsEnabled()
  {
    boolean lAssertionsEnabled = false;
    assert ((lAssertionsEnabled = true) == true);

    if (lAssertionsEnabled)
    {
      System.err.println("WARNING: Assertions are enabled - this will impact performance");
    }

    return lAssertionsEnabled;
  }

  @Override
  public void stateMachineMetaGame(long timeout)
     throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    LOGGER.info("This is " + getName() + " with last commit " + LAST_COMMIT);
    MachineSpecificConfiguration.logConfig();
    if (ASSERTIONS_ENABLED)
    {
      LOGGER.warn("WARNING: Assertions are enabled - this will impact performance");
    }

    mSysStatsLogger = new SystemStatsLogger(mLogName);

    searchProcessor = new GameSearcher(transpositionTableSize, underlyingStateMachine.getRoles().length, mLogName);
    stateMachineProxy.setController(searchProcessor);

    previousTurnRootState = null; //  No move seen yet

    if (!ThreadControl.RUN_SYNCHRONOUSLY)
    {
      Thread lSearchProcessorThread = new Thread(searchProcessor, "Search Processor");
      lSearchProcessorThread.setDaemon(true);
      lSearchProcessorThread.start();
    }

    Random r = new Random();

    mBroadcaster = new Tlkio(getName(), MachineSpecificConfiguration.getCfgStr(CfgItem.TLKIO_CHANNEL));

    // If have been configured with a plan (for test purposes), load it now.
    // We'll still do everything else as normal, but whilst there are moves in
    // the plan, when it comes to play, we'll just play the specified move.
    mPlan = new GamePlan();
    if (mPlanString != null)
    {
      mPlan.considerPlan(convertPlanString(mPlanString));

      //  When given an explicit plan at the start of the game (implies we're in test mode)
      //  warm up the search tree 2 moves before the plan runs out
      GameSearcher.thinkBelowPlanSize = 2;
    }
    else
    {
      GameSearcher.thinkBelowPlanSize = 0;
    }

    ourRole = getRole();
    LOGGER.info("Start clock: " + getMatch().getStartClock() + "s");
    LOGGER.info("Play clock:  " + getMatch().getPlayClock() + "s");
    LOGGER.info("We are:      " + ourRole);

    numRoles = underlyingStateMachine.getRoles().length;
    roleOrdering = underlyingStateMachine.getRoleOrdering();

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
    boolean greedyRolloutsDisabled = MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_GREEDY_ROLLOUTS);

    multiRoleAverageScoreDiff = 0;

    // Set up those game characteristics that we only know now that we've got a state machine.
    mGameCharacteristics.numRoles = numRoles;

    // Check if we already know how to solve this game.
    String lSavedPlan = mGameCharacteristics.getPlan();
    makePreMatchAnnouncement(lSavedPlan);
    if (lSavedPlan != null)
    {
      // We've played this game before and know how to solve it.
      LOGGER.info("Considering saved plan: " + lSavedPlan);
      mPlan.considerPlan(convertPlanString(mGameCharacteristics.getPlan()));
      LOGGER.info("Ready to play");
      return;
    }

    // Analyze game semantics.  This includes latch identification and search filter generation.
    long lSemanticAnalysisStartTime = System.currentTimeMillis();
    long lSemanticAnalysisStopTime = Math.min(timeout - 10000,
                                              lSemanticAnalysisStartTime + (timeout - lSemanticAnalysisStartTime) / 2);

    underlyingStateMachine.performSemanticAnalysis(lSemanticAnalysisStopTime);

    PayoffMatrixGamePlayer lPayoffMatrixGamePlayer;
    try
    {
      lPayoffMatrixGamePlayer = new PayoffMatrixGamePlayer(underlyingStateMachine, lSemanticAnalysisStopTime);
      LOGGER.info("Game can be represented by a simple payoff matrix");
    }
    catch (UnsupportedGameException lEx) {/* Do nothing */}

    CombinedHeuristic heuristic;

    MajorityGoalsHeuristic goalsPredictionHeuristic = new MajorityGoalsHeuristic();
    GoalsStabilityHeuristic goalsStabilityHeuristic = new GoalsStabilityHeuristic();
    PieceHeuristic pieceHeuristic = new PieceHeuristic();

    if (MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_PIECE_HEURISTIC))
    {
      heuristic = new CombinedHeuristic(goalsPredictionHeuristic, goalsStabilityHeuristic);
    }
    else
    {
      heuristic = new CombinedHeuristic(pieceHeuristic, goalsPredictionHeuristic, goalsStabilityHeuristic );//, new AvailableGoalHeuristic() );
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
    mGameCharacteristics.isStrictlyAlternatingPlay = true;

    //  Create masks of possible control props, which we'll whittle down during simulation
    //  If we wind up with a unique prop for each role e'll note it for future use
    ForwardDeadReckonInternalMachineState[] roleControlMasks = new ForwardDeadReckonInternalMachineState[numRoles];
    roleControlProps = new ForwardDeadReckonPropositionInfo[numRoles];

    for(int i = 0; i < numRoles; i++)
    {
      roleControlMasks[i] = underlyingStateMachine.createEmptyInternalState();
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
    double averageHyperSequenceLength = 0;
    double varianceHyperSequenceLength = 0;
    int[] roleScores = new int[numRoles];

    Collection<Factor> factors = underlyingStateMachine.getFactors();

    //	Perform a small number of move-by-move simulations to assess how
    //	the potential piece count heuristics behave at the granularity of
    //	a single decision
    long lMetaGameStartTime = System.currentTimeMillis();
    long lMetaGameStopTime = timeout - 5000;
    int numSamples = 0;
    int maxNumTurns = 0;
    int minNumTurns = Integer.MAX_VALUE;

    // Spend half the time determining heuristic weights if there are any heuristics, else spend
    //  a short time just establishing the type of game
    long lHeuristicStopTime;

    if ( hasHeuristicCandidates )
    {
      lHeuristicStopTime = (lMetaGameStartTime + lMetaGameStopTime) / 2;
    }
    else
    {
      lHeuristicStopTime = lMetaGameStartTime + 4000;
    }

    //  Buffer for new states
    ForwardDeadReckonInternalMachineState newState = new ForwardDeadReckonInternalMachineState(initialState);
    int[] playerMoveParity = new int[numRoles];
    for(int i = 0; i < numRoles; i++)
    {
      playerMoveParity[i] = -1;
    }

    //  Start with greedy rollouts disabled for finding basic characteristics.  This is likely to result in more
    //  simulations, and prevents games with forced greedy paths (like Centipede wherein one choice always leads
    //  to immediate non-win termination and hence is never chosen by greedy processing) giving misleading results
    underlyingStateMachine.enableGreedyRollouts(false, false);

    int totalTurnSamples = 0;
    int totalGoalChangeCount = 0;
    int totalHyperSequenceLength = 0;
    int totalSquaredHyperSequenceLength = 0;
    int totalHyperSequenceCount = 0;
    boolean goalsMonotonic = true;

    //  Slight hack, but for now we don't bother continuing to simulate for a long time after discovering we're in
    //  a simultaneous turn game, because (for now anyway) we disable heuristics in such games anyway
    while ((System.currentTimeMillis() < lHeuristicStopTime) &&
           ((numSamples < MIN_PRIMARY_SIMULATION_SAMPLES) || (!mGameCharacteristics.isSimultaneousMove)))
    {
      ForwardDeadReckonInternalMachineState sampleState = new ForwardDeadReckonInternalMachineState(initialState);

      int numRoleMovesSimulated = 0;
      int numBranchesTaken = 0;
      int turnNum = 0;
      int lastOurGoal = underlyingStateMachine.getGoal(sampleState, ourRole);
      int hyperExpansionLength = -1;
      int previousChoosingRoleIndex;
      int choosingRoleIndex = -2;

      heuristic.tuningStartSampleGame();

      while (!underlyingStateMachine.isTerminal(sampleState))
      {
        boolean roleWithChoiceSeen = false;
        ForwardDeadReckonLegalMoveInfo[] jointMove = new ForwardDeadReckonLegalMoveInfo[numRoles];
        Set<Move> allMovesInState = new HashSet<>();

        previousChoosingRoleIndex = choosingRoleIndex;
        choosingRoleIndex = -2;

        turnNum++;
        hyperExpansionLength++;

        for (int i = 0; i < numRoles; i++)
        {
          //List<Move> legalMoves = underlyingStateMachine.getLegalMovesCopy(sampleState,
          //                                                                 roleOrdering.roleIndexToRole(i));
          List<ForwardDeadReckonLegalMoveInfo> legalMoves = new ArrayList<>(underlyingStateMachine.getLegalMoves(sampleState, roleOrdering.roleIndexToRole(i)));

          if (legalMoves.size() > 1)
          {
            if (roleControlMasks[i].size() > 0)
            {
              if (roleControlMasks[i].intersectionSize(sampleState) == 0)
              {
                LOGGER.debug("Eliminating role control props");
              }
              //  This player has control (may not be only this player)
              roleControlMasks[i].intersect(sampleState);
            }

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

            if ( mGameCharacteristics.isStrictlyAlternatingPlay )
            {
              int moveParity = (turnNum%numRoles);
              if ( playerMoveParity[choosingRoleIndex] == -1 )
              {
                playerMoveParity[choosingRoleIndex] = moveParity;
              }
              else if ( moveParity != playerMoveParity[choosingRoleIndex] )
              {
                mGameCharacteristics.isStrictlyAlternatingPlay = false;
              }
            }

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

        if ( choosingRoleIndex < 0 || (choosingRoleIndex != previousChoosingRoleIndex && previousChoosingRoleIndex >= 0) )
        {
          totalHyperSequenceLength += hyperExpansionLength;
          totalSquaredHyperSequenceLength += hyperExpansionLength*hyperExpansionLength;
          hyperExpansionLength = 0;
          totalHyperSequenceCount++;
        }

        heuristic.tuningInterimStateSample(sampleState, choosingRoleIndex);

        underlyingStateMachine.getNextState(sampleState, null, jointMove, newState);

        sampleState.copy(newState);

        int currentOurGoal = underlyingStateMachine.getGoal(sampleState, ourRole);

        totalTurnSamples++;
        if ( currentOurGoal != lastOurGoal )
        {
          totalGoalChangeCount++;
          if ( lastOurGoal > currentOurGoal )
          {
            goalsMonotonic = false;
          }
          lastOurGoal = currentOurGoal;
        }
      }

      if ( hyperExpansionLength > 0 )
      {
        totalHyperSequenceLength += hyperExpansionLength;
        totalSquaredHyperSequenceLength += hyperExpansionLength*hyperExpansionLength;
        totalHyperSequenceCount++;
      }

      if ( turnNum > maxNumTurns )
      {
        maxNumTurns = turnNum;
      }
      if ( turnNum < minNumTurns )
      {
        assert(turnNum>0);
        minNumTurns = turnNum;
      }

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(sampleState, roleOrdering.roleIndexToRole(i));
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
    LOGGER.info("Performed " + numSamples + " simulations to calculate heuristics");
    if ( numSamples < MIN_PRIMARY_SIMULATION_SAMPLES )
    {
      mGameCharacteristics.isIteratedGame = false;
      heuristic.pruneAll();

      LOGGER.warn("Insufficient sampling time to reliably ascertain game characteristics");
    }
    else
    {
      mGameCharacteristics.hasAdequateSampling = true;
      averageHyperSequenceLength = (double)totalHyperSequenceLength/totalHyperSequenceCount;
      varianceHyperSequenceLength = (double)totalSquaredHyperSequenceLength/totalHyperSequenceCount - averageHyperSequenceLength*averageHyperSequenceLength;
      branchingFactorApproximation /= numSamples;
    }

    //  For now we don't attempt heuristic usage in simultaneous move games
    if ( mGameCharacteristics.isSimultaneousMove )
    {
      heuristic.pruneAll();
    }

    //  If we saw differing game lengths do not treat as an iterated game.  Note that we have to do this
    //  here, and not after the more discriminatory simulation loop later, because the later loop
    //  also tests greedy rollout efficacy, and this can force move paths that give a misleading result for
    //  for the possible range of game lengths.
    //  Similarly we do not consider anything with 4 or less turns as a candidate for iterated analysis
    if (maxNumTurns != minNumTurns || maxNumTurns < 5)
    {
      mGameCharacteristics.isIteratedGame = false;
    }


    mGameCharacteristics.setGoalsStability(goalsStabilityHeuristic.getGoalStability());

    if (mGameCharacteristics.isSimultaneousMove || mGameCharacteristics.isPseudoSimultaneousMove)
    {
      roleControlProps = null;

      greedyRolloutsDisabled = true;
      underlyingStateMachine.enableGreedyRollouts(false, true);
    }
    else
    {
      //  Turn greedy rollouts back on for now to measure their effectiveness
      underlyingStateMachine.enableGreedyRollouts(true, false);

      if ( numRoles == 1 )
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
    long simulationStopTime = Math.min(Math.max(timeout - (mGameCharacteristics.numRoles == 1 ? 12000 : 7000), simulationStartTime + 1000),
                                       simulationStartTime + 10000);

    double averageBranchingFactor = 0;
    double averageNumTurns = 0;
    double averageSquaredNumTurns = 0;
    double averageNumNonDrawTurns = 0;
    int numNonDrawSimulations = 0;
    int numLongDraws = 0;
    int numLongGames = 0;
    int minNumNonDrawTurns = Integer.MAX_VALUE;
    int scoreSum = -1;
    boolean isFixedSum = true;
    PlayoutInfo playoutInfo = underlyingStateMachine.new PlayoutInfo(-1);

    playoutInfo.cutoffDepth = 1000;
    playoutInfo.factor = null;
    playoutInfo.moveWeights = null;

    while (System.currentTimeMillis() < simulationStopTime)
    {
      simulationsPerformed++;

      underlyingStateMachine.getDepthChargeResult(initialState, playoutInfo);

      int netScore = netScore(underlyingStateMachine, null);
      if ( netScore != 50 )
      {
        numNonDrawSimulations++;
        averageNumNonDrawTurns += playoutInfo.playoutLength;
        if ( minNumNonDrawTurns > playoutInfo.playoutLength )
        {
          minNumNonDrawTurns = playoutInfo.playoutLength;
        }
      }

      int thisScoreSum = 0;

      for (int i = 0; i < numRoles; i++)
      {
        roleScores[i] = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(i));

        thisScoreSum += roleScores[i];
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

      if ( scoreSum == -1 )
      {
        scoreSum = thisScoreSum;
      }
      if ( scoreSum != thisScoreSum )
      {
        isFixedSum = false;
      }

      averageNumTurns = (averageNumTurns * (simulationsPerformed - 1) + playoutInfo.playoutLength) /
                        simulationsPerformed;
      averageSquaredNumTurns = (averageSquaredNumTurns *
                                (simulationsPerformed - 1) + playoutInfo.playoutLength *
                                                             playoutInfo.playoutLength) /
                               simulationsPerformed;

      if (playoutInfo.playoutLength < minNumTurns)
      {
        assert(playoutInfo.playoutLength>0);
        minNumTurns = playoutInfo.playoutLength;
      }
      if ( playoutInfo.playoutLength > maxNumTurns)
      {
        maxNumTurns = playoutInfo.playoutLength;
      }

      if ( playoutInfo.playoutLength >= (maxNumTurns*95)/100 )
      {
        numLongGames++;

        if ( netScore == 50 )
        {
          numLongDraws++;
        }
      }

      averageBranchingFactor = (averageBranchingFactor *
                                (simulationsPerformed - 1) + playoutInfo.averageBranchingFactor) /
                               simulationsPerformed;
      mGameCharacteristics.getChoicesHighWaterMark(playoutInfo.averageBranchingFactor);

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
    //	Assume that a game is not really an iterated game unless it's of fixed length
    LOGGER.info("Did " + simulationsPerformed + " simulations for game characteristics");
    if ((Math.abs(branchingFactorApproximation - averageBranchingFactor) > 0.1) || (maxNumTurns != minNumTurns))
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
    mGameCharacteristics.setMinNonDrawLength(minNumNonDrawTurns);
    mGameCharacteristics.setLongDrawsProportion(((double)numLongDraws)/(double)numLongGames);
    mGameCharacteristics.setAverageHyperSequenceLength(averageHyperSequenceLength);
    mGameCharacteristics.setVarianceHyperSequenceLength(varianceHyperSequenceLength);

    mGameCharacteristics.setEarliestCompletionDepth(numRoles*minNumTurns);
    if ( maxNumTurns == minNumTurns )
    {
      mGameCharacteristics.setIsFixedMoveCount();
    }
    if ( isFixedSum )
    {
      mGameCharacteristics.setIsFixedSum();
    }

    //  Dump the game characteristics to trace output
    mGameCharacteristics.report();

    LOGGER.info("Measured goal volatility is " + ((double)totalGoalChangeCount/totalTurnSamples) + (goalsMonotonic ? " [monotonic]" : "[ non-monotonic]"));
    if ( mGameCharacteristics.isPseudoPuzzle )
    {
      if ( totalGoalChangeCount >= totalTurnSamples/4 && goalsMonotonic )
      {
        underlyingStateMachine.setPlayoutPolicy(new PlayoutPolicyGoalGreedyWithPop(underlyingStateMachine));
        searchProcessor.mUseGoalGreedy = true;
      }
      else
      {
        LOGGER.info("Not enabled Goal Greedy policy for pseudo puzzle with insufficient monotonic goal volatility");
      }
    }
    else if ( !mGameCharacteristics.isSimultaneousMove && mGameCharacteristics.getIsFixedSum() && mGameCharacteristics.numRoles == 2 )
    {
      //underlyingStateMachine.setPlayoutPolicy(new PlayoutPolicyLastGoodResponse(underlyingStateMachine));
    }

    heuristic.evaluateSimplicity();

    //  Don't use RAVE with heuristics that use simple application as it will swamp them
    boolean useRAVE = (MachineSpecificConfiguration.getCfgBool(CfgItem.ALLOW_RAVE) &&
                       !mGameCharacteristics.isSimultaneousMove &&
                       numRoles == 2 &&
                       (!pieceHeuristic.isEnabled() || !pieceHeuristic.applyAsSimpleHeuristic()));
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

    if (pieceHeuristic.isEnabled())
    {
      //	Empirically games with piece count heuristics seem to like lower
      //	exploration bias - not entirely sure why!
      explorationBias = explorationBias * 0.7;
    }
    else if ( useRAVE )
    {
      //  If we guiding early expansion with RAVE exploration can be turned down somewhat
      explorationBias *= 0.7;
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
         (mGameCharacteristics.numRoles != 1 || underlyingStateMachine.greedyRolloutEffectiveness == 0) &&
         !underlyingStateMachine.hasNegativelyLatchedGoals() &&
         !underlyingStateMachine.hasPositivelyLatchedGoals()))
    {
      if (!greedyRolloutsDisabled)
      {
        greedyRolloutsDisabled = true;
        underlyingStateMachine.enableGreedyRollouts(false, true);

        //	Scale up the estimate of simulation rate since we'll be running without the overhead
        //	of greedy rollouts (which is proportional to the branching factor)
        simulationsPerformed *= (1 + greedyRolloutCost);
      }
    }

    // Attempt to solve puzzles with A* unless we've already found a solution.
    // !! Do we already know whether the game is a pseudo-puzzle at this point and could we apply A* to such a game?
    if ((!MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_A_STAR)) &&
        (mGameCharacteristics.numRoles == 1) &&
        (observedMaxNetScore < 100) &&
        (factors == null))
    {
      tryAStar(initialState, timeout);

      if ( mPlan != null && !mPlan.isEmpty())
      {
        LOGGER.info("Successfully cached plan from A* so marking as ready to play");
        return;
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

    if ( useRAVE )
    {
      LOGGER.info("Use of RAVE forces sample size of 1");
      mGameCharacteristics.setRolloutSampleSize(1);
    }
    else
    {
      LOGGER.info("Not using RAVE");
      mGameCharacteristics.setRolloutSampleSize(rolloutSampleSize);
    }
    LOGGER.info("Performed " + simulationsPerformed + " simulations in " + (simulationStopTime - simulationStartTime) + "ms");
    LOGGER.info(simulationsPerformed *
                1000 /
                (simulationStopTime - simulationStartTime) +
                " simulations/second performed - setting rollout sample size to " + mGameCharacteristics.getRolloutSampleSize());

    //  If we have any time left try to optimize the way the state machine processes state changes
    if ( timeout > System.currentTimeMillis() + (mGameCharacteristics.numRoles == 1 ? 11000 : 6000) )
    {
      long tuningEnd = Math.min(System.currentTimeMillis() + 5000,timeout - (mGameCharacteristics.numRoles == 1 ? 10000 : 5000));

      underlyingStateMachine.optimizeStateTransitionMechanism(tuningEnd);
    }
    else
    {
      LOGGER.info("Insufficient time remaining for transition mechanism tuning");
    }

    if (!mGameCharacteristics.isIteratedGame || numRoles != 2)
    {
      if (ThreadControl.RUN_SYNCHRONOUSLY)
      {
        mGameCharacteristics.setExplorationBias(explorationBias);
      }

      searchProcessor.mUseRAVE = useRAVE;
      searchProcessor.setup(underlyingStateMachine,
                            initialState,
                            roleOrdering,
                            mGameCharacteristics,
                            greedyRolloutsDisabled,
                            heuristic,
                            mPlan,
                            roleControlProps);
      searchProcessor.startSearch(System.currentTimeMillis() + 60000,
                                  new ForwardDeadReckonInternalMachineState(initialState),
                                  (short)0,
                                  null);

      searchUntil(timeout - SAFETY_MARGIN);
    }

    LOGGER.info("Ready to play");
  }

  private void makePreMatchAnnouncement(String xiSavedPlan)
  {
    String lGameName = getGameName();
    String lAnnouncement;
    if (xiSavedPlan != null)
    {
      lAnnouncement = "Yawn - I am playing " + lGameName;
    }
    else if (lGameName.charAt(0) >= '0' && lGameName.charAt(0) <= '9')
    {
      lAnnouncement = "Ooh - I am playing an exciting new game";
    }
    else
    {
      lAnnouncement = "I am playing " + getGameName();
    }

    lAnnouncement += " as " + ourRole + " (Match ID " + getMatch().getMatchId() + ")";
    mBroadcaster.broadcast(lAnnouncement);
  }

  private void makePostMatchAnnouncement(boolean xiAborted)
  {
    String lAnnouncement;

    if (xiAborted)
    {
      lAnnouncement = "Match aborted.  And I was having so much fun.";
    }
    else
    {
      if (mFinalScore == 100)
      {
        if ((mGameCharacteristics.isPseudoPuzzle) || (mGameCharacteristics.getPlan() != null))
        {
          lAnnouncement = "I score 100.  That wasn't so puzzling, was it?";
        }
        else
        {
          lAnnouncement = "Perfect 100.  You'll need to try harder than that.";
        }
      }
      else if (mSolvedFromStart)
      {
        lAnnouncement = "I scored " + mFinalScore +
                                           ".  You might think that's poor, but it's the best available in this game.";
      }
      else if (mFinalScore == 0)
      {
        lAnnouncement = "Hmm, that didn't work so well.  ";
        if (mGameCharacteristics.isPseudoPuzzle)
        {
          lAnnouncement += "Looks like a need some practice.";
        }
        else
        {
          lAnnouncement += "I'll beat you next time though.";
        }
      }
      else
      {
        lAnnouncement = "I scored " + mFinalScore + ".  I don't really know what to make of that.";
      }
    }

    // If we're tidying after watchdog pop, we may not have a broadcaster.
    if (mBroadcaster != null)
    {
      mBroadcaster.broadcast(lAnnouncement);
    }
  }

  private void tryAStar(ForwardDeadReckonInternalMachineState xiInitialState, long xiTimeout)
  {
    // 8-puzzle type stuff.
    LOGGER.info("Puzzle with no observed solution");

    MachineState lTerminalState;
    Set<MachineState> lGoalStates = underlyingStateMachine.findGoalStates(getRole(), 90, 500, 20);
    Set<MachineState> lCleanedStates = new HashSet<>();

    for (MachineState lState : lGoalStates)
    {
      Set<GdlSentence> lEliminatedSentences = new HashSet<>();

      for (GdlSentence lSentence : lState.getContents())
      {
        int lCount = 0;

        for (MachineState lSecondState : lGoalStates)
        {
          if (lState != lSecondState &&
              unNormalizedStateDistance(lState, lSecondState) == 1 &&
              !lSecondState.getContents().contains(lSentence))
          {
            lCount++;
          }
        }

        if (lCount > 1)
        {
          lEliminatedSentences.add(lSentence);
        }
      }

      MachineState lCleaned = new MachineState(new HashSet<>(lState.getContents()));
      lCleaned.getContents().removeAll(lEliminatedSentences);

      lCleanedStates.add(lCleaned);
    }

    if (!lCleanedStates.isEmpty())
    {
      lTerminalState = lCleanedStates.iterator().next();

      LOGGER.info("Found target state: " + lTerminalState);

      int lTargetStateSize = lTerminalState.getContents().size();

      if (lTargetStateSize < Math.max(2, xiInitialState.size() / 2))
      {
        LOGGER.info("Unsuitable target state based on state elimination - ignoring");
      }
      else
      {
        Collection<Move> lSolution = new TargetedSolutionStatePlayer(underlyingStateMachine,
                                                                     lTerminalState,
                                                                     this).attemptAStarSolve(99,
                                                                                             xiTimeout - SAFETY_MARGIN);
        if (lSolution != null)
        {
          mPlan.considerPlan(lSolution);
          LOGGER.info("Solved by A*");
        }
      }
    }
  }

  private ForwardDeadReckonLegalMoveInfo findMoveInfo(Role role, GdlTerm moveTerm)
  {
    Move move = underlyingStateMachine.getMoveFromTerm(moveTerm);
    HashSet<PolymorphicProposition> legalsForRole = new HashSet<>();
    legalsForRole.addAll(Arrays.asList(underlyingStateMachine.getFullPropNet().getLegalPropositions().get(role)));
    Map<PolymorphicProposition,PolymorphicProposition> legalInputMap = underlyingStateMachine.getFullPropNet().getLegalInputMap();

    for(ForwardDeadReckonLegalMoveInfo info : underlyingStateMachine.getFullPropNet().getMasterMoveList())
    {
      if ( info.move.equals(move) && info.inputProposition != null )
      {
        PolymorphicProposition legalProp = legalInputMap.get(info.inputProposition);
        if ( legalsForRole.contains(legalProp))
        {
          return info;
        }
      }
    }

    return null;
  }

  @Override
  public Move stateMachineSelectMove(long timeout)
    throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
    // Kick the watchdog
    mWatchdog.alive();

    // We get the current start time
    long start = System.currentTimeMillis();
    long finishBy = timeout - SAFETY_MARGIN;
    Move bestMove;
    List<Move> moves;
    List<GdlTerm> lastJointMove = getMatch().getMostRecentMoves();

    if ( lastJointMove != null )
    {
      LOGGER.info("Moves played for turn " + mTurn + ": " + lastJointMove);

      for(int i = 0; i < numRoles; i++)
      {
        if ( previousTurnRootState != null && roleControlProps != null )
        {
          if ( previousTurnRootState.contains(roleControlProps[i]) )
          {
            lastMove = findMoveInfo(roleOrdering.roleIndexToRole(i), lastJointMove.get(roleOrdering.roleIndexToRawRoleIndex(i)));
            LOGGER.info("Non-null move last turn was: " + lastMove);
            break;
          }
        }
        else
        {
          lastMove = findMoveInfo(roleOrdering.roleIndexToRole(i), lastJointMove.get(roleOrdering.roleIndexToRawRoleIndex(i)));
          if ( lastMove != null && lastMove.inputProposition != null )
          {
            LOGGER.info("Non-null move last turn was: " + lastMove);
            break;
          }
        }
      }
    }

    mTurn++;
    LOGGER.info("Starting turn " + mTurn);
    StatsLogUtils.Series.TURN.logDataPoint(start, mTurn);

    ForwardDeadReckonInternalMachineState currentState;

    searchProcessor.requestYield(true);

    // Check that the last move as reported by the server is the same that was sent.  Flag up if they differ because
    // there has obviously been some communication problem.
    if (mLastMove != null)
    {
      Move lLastMove = new Move(getMatch().getMostRecentMoves().get(roleOrdering.getOurRawRoleIndex()));
      if (!mLastMove.equals(lLastMove))
      {
        LOGGER.error("Wrong move recorded!  Submitted move was " + mLastMove + " but the server reported " + lLastMove);
      }
    }

    LOGGER.debug("Calculating current state");

    synchronized (searchProcessor.getSerializationObject())
    {
      underlyingStateMachine.noteTurnNumber(mTurn);

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

      previousTurnRootState = currentState;
    }

//    if ( localSearchStateMachine == null )
//    {
//      localSearchStateMachine = underlyingStateMachine.createInstance();
//    }

    if (mPlan != null && mPlan.size() > GameSearcher.thinkBelowPlanSize)
    {
      // We have a pre-prepared plan.  Simply play the next move.
      bestMove = mPlan.nextMove();
      LOGGER.info("Playing pre-planned move: " + bestMove);

      if (GameSearcher.thinkBelowPlanSize > 0)
      {
        // We need to keep the search 'up with' the plan to make forced-play testing work properly, or else the search
        // will not be 'primed' during forced play when the plan runs out.
        searchProcessor.startSearch(finishBy, currentState, currentMoveDepth, null);
        currentMoveDepth += numRoles;
      }
    }
    else if (mGameCharacteristics.isIteratedGame && numRoles == 2)
    {
      IteratedGamePlayer iteratedPlayer = new IteratedGamePlayer(underlyingStateMachine, this, mGameCharacteristics.isPseudoSimultaneousMove, roleOrdering, mGameCharacteristics.competitivenessBonus);
      bestMove = iteratedPlayer.selectMove(moves, timeout);
      LOGGER.info("Playing best iterated game move: " + bestMove);
    }
    else
    {
      LOGGER.debug("Setting search root");
      searchProcessor.startSearch(finishBy, currentState, currentMoveDepth, lastMove);
      currentMoveDepth += numRoles;

      searchProcessor.requestYield(false);

      LOGGER.debug("Waiting for processing");
      searchUntil(finishBy);

      LOGGER.debug("Time to submit order - ask GameSearcher to yield");
      searchProcessor.requestYield(true);

      long getBestMoveStartTime = System.currentTimeMillis();
      bestMove = searchProcessor.getBestMove();
      if ( System.currentTimeMillis() - getBestMoveStartTime > 250 )
      {
        LOGGER.warn("Retrieving the best move took " + (System.currentTimeMillis() - getBestMoveStartTime) + "ms");
      }

      if (!moves.contains(bestMove))
      {
        LOGGER.warn("Selected illegal move!!");
        assert(false);
        bestMove = moves.get(0);
      }
      LOGGER.info("Playing move: " + bestMove);

      searchProcessor.requestYield(false);
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

    mLastMove = bestMove;

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
          long sleepForUpTo = xiFinishBy - System.currentTimeMillis();

          if ( sleepForUpTo > 0 )
          {
            Thread.sleep(Math.min(250, sleepForUpTo));
          }
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

      if (mTurn <= 1)
      {
        // We've completed the root, either during meta-gaming or during the first turn.  As a result, this game is
        // completely solved.
        mSolvedFromStart = true;
      }
    }
  }

  @Override
  public void stateMachineStop()
  {
    // Log the final score.
    synchronized (searchProcessor.getSerializationObject())
    {
      ForwardDeadReckonInternalMachineState lState = underlyingStateMachine.createInternalState(getCurrentState());
      mFinalScore = underlyingStateMachine.getGoal(lState, ourRole);
      LOGGER.info("Final score: " + mFinalScore);
      StatsLogUtils.Series.SCORE.logDataPoint(mFinalScore);
      StatsLogUtils.Series.SAMPLE_RATE.logDataPoint(mGameCharacteristics.getRolloutSampleSize());
    }

    // If we've just solved a puzzle for the first time, save the game history as a plan.
    if (((mFinalScore == 100) || (mSolvedFromStart)) &&
        (mGameCharacteristics.numRoles == 1) &&
        (mGameCharacteristics.getPlan() == null))
    {
      mGameCharacteristics.setPlan(convertHistoryToPlan());
    }

    makePostMatchAnnouncement(false);
    tidyUp(true, false);
  }

  @Override
  public void stateMachineAbort()
  {
    LOGGER.warn("Game aborted by server");
    makePostMatchAnnouncement(true);
    tidyUp(true, false);
  }

  @Override
  public void expired()
  {
    LOGGER.warn("Game aborted on watchdog expiry");
    makePostMatchAnnouncement(true);
    tidyUp(false, false);
  }

  /**
   * Tidy up game state at the end of the game.
   *
   * @param xiRegularTermination - whether the termination is regular (stop/abort) or irregular (watchdog).
   * @param xiNewMatch - whether the start of a new match triggered this tidy-up.
   */
  private void tidyUp(boolean xiRegularTermination, boolean xiNewMatch)
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

    if (mBroadcaster != null)
    {
      mBroadcaster.stop();
      mBroadcaster = null;
    }

    if (mWatchdog != null)
    {
      mWatchdog.stop();
      mWatchdog = null;
    }

    ThreadControl.tidyUp();

    // Save anything that we've learned about this game.
    if (xiRegularTermination)
    {
      mGameCharacteristics.saveConfig();
    }

    // Tidy up the proxy.
    if (stateMachineProxy != null)
    {
      stateMachineProxy.setController(null);
      stateMachineProxy = null;
    }

    // Free off all our references.
    ourRole                      = null;
    mPlanString                  = null;
    mPlan                        = null;
    roleOrdering                 = null;
    roleControlProps             = null;
    underlyingStateMachine       = null;
    previousTurnRootState        = null;
    mLogName                     = null;
    mLastMove                    = null;
    lastMove                     = null;

    if (!xiNewMatch)
    {
      // Get our parent to tidy up too.
      cleanupAfterMatch();

      // Free off the static pools.
      GdlPool.drainPool();
      SymbolPool.drainPool();

      // Prompt the JVM to do garbage collection, because we've hopefully just freed a lot of stuff.
      long endGCTime = System.currentTimeMillis() + 3000;
      for (int ii = 0; ii < 1000 && System.currentTimeMillis() < endGCTime; ii++)
      {
        System.gc();
        try {Thread.sleep(1);} catch (InterruptedException lEx) {/* Whatever */}
      }
    }

    LOGGER.info("Tidy-up complete");
  }

  // Methods for use by UTs only
  public boolean utWillBeTerminal() throws TransitionDefinitionException
  {
    MachineState lState = getStateMachine().getNextState(getCurrentState(),
                                                         Collections.singletonList(mLastMove));
    return getStateMachine().isTerminal(lState);
  }

  public int utGetFinalScore()
  {
    return mFinalScore;
  }
}
