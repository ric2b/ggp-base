
package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.factory.OptimizingPolymorphicPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponent;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponentFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropnetFastAnimator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.query.ProverQueryBuilder;
import org.ggp.base.util.stats.Stats;

public class ForwardDeadReckonPropnetStateMachine extends StateMachine
{
  private static final Logger LOGGER = LogManager.getLogger();

  /** The underlying proposition network - in various optimised forms. */

  // The complete propnet.
  private ForwardDeadReckonPropNet                                     fullPropNet                     = null;

  // A propnet containing just those components required to compute the goal values when it's already known that the
  // state is terminal.
  private ForwardDeadReckonPropNet                                     goalsNet                        = null;

  // The propnet is split into two networks dependent on the proposition which changes most frequently during metagame
  // simulations.  This is commonly a "control" proposition identifing which player's turn it is in a non-simultaneous
  // game.  The X-net contains just those components required when the control proposition is true.  The O-net contains
  // just those components required when the control proposition is false.
  //
  // In games without greedy rollouts, these are set to the goalless version (below).
  private ForwardDeadReckonPropNet                                     propNetX                        = null;
  private ForwardDeadReckonPropNet                                     propNetO                        = null;

  // X- and O-nets without the goal calculation logic (which is in goalsNet).
  private ForwardDeadReckonPropNet                                     propNetXWithoutGoals            = null;
  private ForwardDeadReckonPropNet                                     propNetOWithoutGoals            = null;

  // The propnet that is currently in use (one of the X- or O- nets).
  private ForwardDeadReckonPropNet                                     propNet                         = null;

  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositionsX              = null;
  private Map<Role, Move[]>                                            legalPropositionMovesX          = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositionsO              = null;
  private Map<Role, Move[]>                                            legalPropositionMovesO          = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositions               = null;
  /** The player roles */
  private int                                                          numRoles;
  private List<Role>                                                   roles;
  private ForwardDeadReckonInternalMachineState                        lastInternalSetStateX           = null;
  private ForwardDeadReckonInternalMachineState                        lastInternalSetStateO           = null;
  private ForwardDeadReckonInternalMachineState                        lastInternalSetState            = null;
  private final boolean                                                useSampleOfKnownLegals          = false;
  private GdlSentence                                                  XSentence                       = null;
  private GdlSentence                                                  OSentence                       = null;
  private ForwardDeadReckonPropositionCrossReferenceInfo               XSentenceInfo                   = null;
  private ForwardDeadReckonPropositionCrossReferenceInfo               OSentenceInfo                   = null;
  private MachineState                                                 initialState                    = null;
  private ForwardDeadReckonProposition[]                               moveProps                       = null;
  private boolean                                                      measuringBasePropChanges        = false;
  private Map<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> basePropChangeCounts            = new HashMap<>();
  private ForwardDeadReckonProposition[]                               chosenJointMoveProps            = null;
  private Move[]                                                       chosenMoves                     = null;
  private ForwardDeadReckonProposition[]                               previouslyChosenJointMovePropsX = null;
  private ForwardDeadReckonProposition[]                               previouslyChosenJointMovePropsO = null;
  private int[]                                                        previouslyChosenJointMovePropIdsX = null;
  private int[]                                                        previouslyChosenJointMovePropIdsO = null;
  private ForwardDeadReckonPropositionCrossReferenceInfo[]             masterInfoSet                   = null;
  private StateMachine                                                 validationMachine               = null;
  private MachineState                                                 validationState                 = null;
  private int                                                          instanceId;
  private int                                                          maxInstances;
  private long                                                         metagameTimeout                 = 20000;
  private int                                                          numInstances                    = 1;
  private Set<Factor>                                                  factors                         = null;
  public long                                                          totalNumGatesPropagated         = 0;
  public long                                                          totalNumPropagates              = 0;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mPositiveGoalLatches      = null;
  private Map<PolymorphicProposition, ForwardDeadReckonInternalMachineState> mNegativeGoalLatches      = null;

  private class TestPropnetStateMachineStats extends Stats
  {
    private long totalResets;
    private int  numStateSettings;
    private long totalGets;
    private int  numStateFetches;
    private int  numBaseProps;
    private int  numInputs;
    private int  numLegals;

    public TestPropnetStateMachineStats(int numBaseProps,
                                        int numInputs,
                                        int numLegals)
    {
      this.numBaseProps = numBaseProps;
      this.numInputs = numInputs;
      this.numLegals = numLegals;
    }

    @Override
    public void clear()
    {
      totalResets = 0;
      numStateSettings = 0;
      totalGets = 0;
      numStateFetches = 0;
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();

      sb.append("#base props: " + numBaseProps);
      sb.append("\n");
      sb.append("#inputs: " + numInputs);
      sb.append("\n");
      sb.append("#legals: " + numLegals);
      sb.append("\n");
      sb.append("#state sets: " + numStateSettings);
      sb.append("\n");
      if (numStateSettings > 0)
      {
        sb.append("Average #components reset per state set: " + totalResets /
                  numStateSettings);
        sb.append("\n");
      }
      sb.append("#state gets: " + numStateFetches);
      sb.append("\n");
      if (numStateFetches > 0)
      {
        sb.append("Average #components queried per state get: " + totalGets /
                  numStateFetches);
        sb.append("\n");
      }

      return sb.toString();
    }
  }

  public class MoveWeights
  {
    public double[]  weightScore;
    private int      numSamples = 1;
    private double   total      = 0;
    private int      weightSize;
    private double[] averageScores;

    public MoveWeights(int vectorSize, int numRoles)
    {
      weightSize = vectorSize;
      weightScore = new double[vectorSize];
      averageScores = new double[numRoles];
      clear();
    }

    public void clear()
    {
      total = weightSize * 50;
      numSamples = 1;

      for (int i = 0; i < weightSize; i++)
      {
        weightScore[i] = 50;
      }
    }

    public MoveWeights copy()
    {
      MoveWeights result = new MoveWeights(weightSize, averageScores.length);

      for (int i = 0; i < weightScore.length; i++)
      {
        result.weightScore[i] = weightScore[i];
      }

      result.numSamples = numSamples;
      result.total = total;

      return result;
    }

    public void addSample(double[] scores,
                          List<ForwardDeadReckonLegalMoveInfo> moves)
    {
      for (ForwardDeadReckonLegalMoveInfo move : moves)
      {
        double score = scores[move.roleIndex];

        double oldWeight = weightScore[move.globalMoveIndex];
        double newWeigth = (oldWeight * numSamples + score) / (numSamples + 1);
        weightScore[move.globalMoveIndex] = newWeigth;

        total += (newWeigth - oldWeight);
      }
      numSamples++;
    }

    public void addResult(double[] scores, ForwardDeadReckonLegalMoveInfo move)
    {
      double score = scores[move.roleIndex];

      double oldWeight = weightScore[move.globalMoveIndex];
      double newWeigth = (oldWeight * numSamples + score) / (numSamples + 1);
      weightScore[move.globalMoveIndex] = newWeigth;

      total += (newWeigth - oldWeight);
    }

    public void noteSampleComplete()
    {
      numSamples++;
    }

    public void accumulate(MoveWeights other)
    {
      total = 0;

      for (int i = 0; i < weightSize; i++)
      {
        weightScore[i] = (weightScore[i] * numSamples + other.weightScore[i] *
                                                        other.numSamples) /
                         (numSamples + other.numSamples);
        total += weightScore[i];
      }

      numSamples += other.numSamples;
    }

    public double getAverage()
    {
      return total / weightSize;
    }

    public double getStdDeviation()
    {
      double var = 0;
      double mean = total / weightSize;

      for (int i = 0; i < weightSize; i++)
      {
        var += (weightScore[i] - mean) * (weightScore[i] - mean);
      }

      return Math.sqrt(var / weightSize);
    }
  }

  private TestPropnetStateMachineStats stats;

  public Stats getStats()
  {
    return stats;
  }

  private ForwardDeadReckonInternalMachineState createInternalState(ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet,
                                                                    GdlSentence XSentence,
                                                                    MachineState state)
  {
    //ProfileSection methodSection = new ProfileSection("InternalMachineState.constructFromMachineState");
    //try
    {
      ForwardDeadReckonInternalMachineState result = new ForwardDeadReckonInternalMachineState(infoSet);

      for (GdlSentence s : state.getContents())
      {
        ForwardDeadReckonProposition p = (ForwardDeadReckonProposition)propNet.getBasePropositions().get(s);
        ForwardDeadReckonPropositionInfo info = p.getInfo();
        result.add(info);

        result.isXState |= (info.sentence == XSentence);
      }

      LOGGER.trace("Created internal state: " + result + " with hash " + result.hashCode());
      return result;
    }
    //finally
    //{
    //	methodSection.exitScope();
    //}
  }

  public ForwardDeadReckonPropositionCrossReferenceInfo[] getInfoSet()
  {
    return masterInfoSet;
  }

  public ForwardDeadReckonInternalMachineState createInternalState(MachineState state)
  {
    return createInternalState(masterInfoSet, XSentence, state);
  }

  /**
   * Find latches.
   */
  // !! ARR Work in progress - will need to return something
  public void findLatches()
  {
    // As a quick win for now, we'll keep a simple record of any propositions which latch a goal proposition (either
    // positively or negatively).
    mPositiveGoalLatches = new HashMap<>();
    mNegativeGoalLatches = new HashMap<>();
    for (PolymorphicProposition lGoals[] : fullPropNet.getGoalPropositions().values())
    {
      for (PolymorphicProposition lGoal : lGoals)
      {
        mPositiveGoalLatches.put(lGoal, new ForwardDeadReckonInternalMachineState(masterInfoSet));
        mNegativeGoalLatches.put(lGoal, new ForwardDeadReckonInternalMachineState(masterInfoSet));
      }
    }

    // Look for the simplest kind of latch - some logic turns a base proposition on.  After that, it stays on.
    //
    //            Logic
    //              |
    //              v
    // BasePropX -> Or -> Transition -\
    //     ^                          |
    //     |                          |
    //     \__________________________/
    //
    // This identifies, for example, the "lost" proposition in base.firefighter and the cell-has-an-x and cell-has-an-o
    // propositions in TTT (and, presumably, most of the other board-filling games too).
    //
    //
    // Do likewise for the simplest negative latches.  (!! ARR Not sure this actually catches anything.  Maybe because
    // there's an OR from some sort of INIT prop??)
    //
    //             Logic
    //               |
    //               v
    // BasePropX -> And -> Transition -\
    //     ^                           |
    //     |                           |
    //     \___________________________/
    //

    for (PolymorphicProposition lBaseProp : fullPropNet.getBasePropositionsArray())
    {
      if (lBaseProp.getSingleInput() instanceof PolymorphicTransition)
      {
        PolymorphicTransition lTransition = (PolymorphicTransition)lBaseProp.getSingleInput();
        PolymorphicComponent lCandidateGate = lTransition.getSingleInput();

        if ((lCandidateGate instanceof PolymorphicOr) ||
            (lCandidateGate instanceof PolymorphicAnd))
        {
          boolean lPositive = (lCandidateGate instanceof PolymorphicOr);
          for (PolymorphicComponent lFeeder : lCandidateGate.getInputs())
          {
            if (lFeeder == lBaseProp)
            {
              // Found a latching proposition.  Find out if anything else is latched, positively or negatively, as a
              // result.
              LOGGER.debug("Latch(" + (lPositive ? "+" : "-") + "ve): " + lBaseProp);
              Set<PolymorphicComponent> lPositivelyLatched = new HashSet<>();
              Set<PolymorphicComponent> lNegativelyLatched = new HashSet<>();
              findAllLatchedStatesFor(lBaseProp,
                                      lPositive,
                                      (ForwardDeadReckonProposition)lBaseProp,
                                      lPositivelyLatched,
                                      lNegativelyLatched);
            }
          }
        }
      }
    }

    // Post-process the goal latches to remove any goals for which no latches were found.
    Iterator<Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState>> lIterator =
                                                                             mPositiveGoalLatches.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.info("Goal '" + lGoal + "' is positively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mPositiveGoalLatches.isEmpty())
    {
      LOGGER.info("No positive goal latches");
      mPositiveGoalLatches = null;
    }

    lIterator = mNegativeGoalLatches.entrySet().iterator();
    while (lIterator.hasNext())
    {
      Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry = lIterator.next();
      PolymorphicProposition lGoal = lEntry.getKey();
      ForwardDeadReckonInternalMachineState lLatches = lEntry.getValue();

      if (lLatches.size() != 0)
      {
        LOGGER.info("Goal '" + lGoal + "' is negatively latched by any of: " + lLatches);
      }
      else
      {
        lIterator.remove();
      }
    }

    if (mNegativeGoalLatches.isEmpty())
    {
      LOGGER.info("No negative goal latches");
      mNegativeGoalLatches = null;
    }
  }

  private void findAllLatchedStatesFor(PolymorphicComponent xiComponent,
                                       boolean xiForcedOutputValue,
                                       ForwardDeadReckonProposition xiOriginal,
                                       Set<PolymorphicComponent> xiPositivelyLatched,
                                       Set<PolymorphicComponent> xiNegativelyLatched)
  {
    // Check if we've already visited this component.  (This is expected, because we do latching through transitions.)
    if ((xiPositivelyLatched.contains(xiComponent)) || (xiNegativelyLatched.contains(xiComponent)))
    {
      return;
    }

    // Record the forced value of this component.
    assert(Collections.disjoint(xiPositivelyLatched, xiNegativelyLatched));
    if (xiForcedOutputValue)
    {
      xiPositivelyLatched.add(xiComponent);
    }
    else
    {
      xiNegativelyLatched.add(xiComponent);
    }
    assert(Collections.disjoint(xiPositivelyLatched, xiNegativelyLatched));

    // Check which downstream components are latched as a result.
    for (PolymorphicComponent lComp : xiComponent.getOutputs())
    {
      if (lComp instanceof PolymorphicProposition)
      {
        LOGGER.debug("  Latched(" + (xiForcedOutputValue ? "+" : "-") + "ve): " + lComp);
        findAllLatchedStatesFor(lComp, xiForcedOutputValue, xiOriginal, xiPositivelyLatched, xiNegativelyLatched);

        // If we've just negatively latched a LEGAL prop, also negatively latch the corresponding DOES prop.
        if (!xiForcedOutputValue)
        {
          // Find the matching proposition in the legal/input map.  (If we don't have a LEGAL/DOES prop in hand then we
          // won't find anything.  Also, it can't be a DOES prop in hand because they can't ever have logic leading to
          // them.  So, if we do find a match, it's the DOES prop corresponding to the LEGAL prop in hand.)
          PolymorphicProposition lDoesProp = fullPropNet.getLegalInputMap().get(lComp);
          if (lDoesProp != null)
          {
            LOGGER.debug("  Latched(-ve): " + lDoesProp);
            findAllLatchedStatesFor(lComp, xiForcedOutputValue, xiOriginal, xiPositivelyLatched, xiNegativelyLatched);
          }
        }

        // If we've just latched a goal prop, remember it.
        if (mPositiveGoalLatches.containsKey(lComp))
        {
          if (xiForcedOutputValue)
          {
            mPositiveGoalLatches.get(lComp).add(xiOriginal.getInfo());
          }
          else
          {
            mNegativeGoalLatches.get(lComp).add(xiOriginal.getInfo());
          }
        }
      }
      else if ((lComp instanceof PolymorphicOr) && (xiForcedOutputValue))
      {
        // This OR gate will always have a true input, therefore the output will always be true.
        findAllLatchedStatesFor(lComp, xiForcedOutputValue, xiOriginal, xiPositivelyLatched, xiNegativelyLatched);
      }
      else if ((lComp instanceof PolymorphicAnd) && (!xiForcedOutputValue))
      {
        // This AND gate will always have a false input, therefore the output will always be false.
        findAllLatchedStatesFor(lComp, xiForcedOutputValue, xiOriginal, xiPositivelyLatched, xiNegativelyLatched);
      }
      else if (lComp instanceof PolymorphicNot)
      {
        findAllLatchedStatesFor(lComp, !xiForcedOutputValue, xiOriginal, xiPositivelyLatched, xiNegativelyLatched);
      }
      else if (lComp instanceof PolymorphicTransition)
      {
        findAllLatchedStatesFor(lComp, xiForcedOutputValue, xiOriginal, xiPositivelyLatched, xiNegativelyLatched);
      }
    }
  }

  public Integer getLatchedScore(ForwardDeadReckonInternalMachineState xiState)
  {
    if (mPositiveGoalLatches != null)
    {
      for (Entry<PolymorphicProposition, ForwardDeadReckonInternalMachineState> lEntry : mPositiveGoalLatches.entrySet())
      {
        if (xiState.intersects(lEntry.getValue()))
        {
          return Integer.parseInt(lEntry.getKey().getName().getBody().get(1).toString());
        }
      }
    }

    return null;
  }

  public Set<MachineState> findTerminalStates(int maxResultSet, int maxDepth)
  {
    PolymorphicProposition terminal = fullPropNet.getTerminalProposition();

    return findSupportStates(terminal.getName(), maxResultSet, maxDepth);
  }

  public Set<MachineState> findGoalStates(Role role,
                                          int minValue,
                                          int maxResultSet,
                                          int maxDepth)
  {
    Set<MachineState> results = new HashSet<>();

    for (PolymorphicProposition p : fullPropNet.getGoalPropositions().get(role))
    {
      if (Integer.parseInt(p.getName().getBody().get(1).toString()) >= minValue)
      {
        results.addAll(findSupportStates(p.getName(), maxResultSet, maxDepth));
      }
    }

    return results;
  }

  private class AntecedantCursor
  {
    public Set<PolymorphicProposition> positiveProps;
    public Set<PolymorphicProposition> negativeProps;
    public boolean                     isPositive;

    public AntecedantCursor()
    {
      positiveProps = new HashSet<>();
      negativeProps = new HashSet<>();
      isPositive = true;
    }

    public AntecedantCursor(AntecedantCursor parent)
    {
      positiveProps = new HashSet<>(parent.positiveProps);
      negativeProps = new HashSet<>(parent.negativeProps);
      isPositive = parent.isPositive;
    }

    public boolean compatibleWith(AntecedantCursor other)
    {
      if (isPositive == other.isPositive)
      {
        for (PolymorphicProposition p : positiveProps)
        {
          if (other.negativeProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.positiveProps)
        {
          if (negativeProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : negativeProps)
        {
          if (other.positiveProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.negativeProps)
        {
          if (positiveProps.contains(p))
          {
            return false;
          }
        }
      }
      else
      {
        for (PolymorphicProposition p : positiveProps)
        {
          if (other.positiveProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.positiveProps)
        {
          if (positiveProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : negativeProps)
        {
          if (other.negativeProps.contains(p))
          {
            return false;
          }
        }
        for (PolymorphicProposition p : other.negativeProps)
        {
          if (negativeProps.contains(p))
          {
            return false;
          }
        }
      }

      return true;
    }

    public boolean compatibleWithAll(Set<AntecedantCursor> set)
    {
      for (AntecedantCursor c : set)
      {
        if (!compatibleWith(c))
        {
          return false;
        }
      }

      return true;
    }

    public void unionInto(AntecedantCursor c)
    {
      if (c.isPositive == isPositive)
      {
        c.positiveProps.addAll(positiveProps);
        c.negativeProps.addAll(negativeProps);
      }
      else
      {
        c.positiveProps.addAll(negativeProps);
        c.negativeProps.addAll(positiveProps);
      }
    }

    public void unionInto(Set<AntecedantCursor> set)
    {
      if (set.isEmpty())
      {
        set.add(this);
      }
      else
      {
        for (AntecedantCursor c : set)
        {
          unionInto(c);
        }
      }
    }
  }

  public void validateStateEquality(ForwardDeadReckonPropnetStateMachine other)
  {
    if (!lastInternalSetState.equals(other.lastInternalSetState))
    {
      LOGGER.warn("Last set state mismtch");
    }

    for (PolymorphicProposition p : propNet.getBasePropositionsArray())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;

      if (fdrp.getValue(instanceId) != fdrp.getValue(other.instanceId))
      {
        LOGGER.warn("Base prop state mismatch on: " + p);
      }
    }
  }

  private Set<AntecedantCursor> addPropositionAntecedants(PolymorphicPropNet pn,
                                                          PolymorphicComponent p,
                                                          AntecedantCursor cursor,
                                                          int maxResultSet,
                                                          int maxDepth,
                                                          int depth)
  {
    if (depth >= maxDepth)
    {
      return null;
    }

    if (p instanceof PolymorphicTransition)
    {
      return null;
    }
    else if (p instanceof PolymorphicProposition)
    {
      PolymorphicProposition prop = (PolymorphicProposition)p;

      if (pn.getBasePropositions().values().contains(prop))
      {
        AntecedantCursor newCursor = new AntecedantCursor(cursor);

        if (cursor.isPositive)
        {
          if (!cursor.negativeProps.contains(p))
          {
            newCursor.positiveProps.add(prop);
            Set<AntecedantCursor> result = new HashSet<>();
            result.add(newCursor);
            return result;
          }
          else if (!cursor.positiveProps.contains(p))
          {
            newCursor.negativeProps.add(prop);
            Set<AntecedantCursor> result = new HashSet<>();
            result.add(newCursor);
            return result;
          }
          else
          {
            return null;
          }
        }
        if (!cursor.positiveProps.contains(p))
        {
          newCursor.negativeProps.add(prop);
          Set<AntecedantCursor> result = new HashSet<>();
          result.add(newCursor);
          return result;
        }
        else if (!cursor.negativeProps.contains(p))
        {
          newCursor.positiveProps.add(prop);
          Set<AntecedantCursor> result = new HashSet<>();
          result.add(newCursor);
          return result;
        }
        else
        {
          return null;
        }
      }

      return addPropositionAntecedants(pn,
                                       p.getSingleInput(),
                                       cursor,
                                       maxResultSet,
                                       maxDepth,
                                       depth + 1);
    }
    else if (p instanceof PolymorphicNot)
    {
      cursor.isPositive = !cursor.isPositive;
      Set<AntecedantCursor> result = addPropositionAntecedants(pn,
                                                               p.getSingleInput(),
                                                               cursor,
                                                               maxResultSet,
                                                               maxDepth,
                                                               depth + 1);
      cursor.isPositive = !cursor.isPositive;

      return result;
    }
    else if (p instanceof PolymorphicAnd)
    {
      Set<AntecedantCursor> subResults = new HashSet<>();

      for (PolymorphicComponent c : p.getInputs())
      {
        if (subResults.size() > maxResultSet)
        {
          return null;
        }

        AntecedantCursor newCursor = new AntecedantCursor(cursor);
        Set<AntecedantCursor> inputResults = addPropositionAntecedants(pn,
                                                                       c,
                                                                       newCursor,
                                                                       maxResultSet,
                                                                       maxDepth,
                                                                       depth + 1);
        if (inputResults == null)
        {
          //	No positive matches in an AND that requires a positive result => failure
          if (cursor.isPositive)
          {
            return null;
          }
        }
        else
        {
          if (cursor.isPositive)
          {
            //	We require ALL inputs, so take the conditions gathered for this one and validate
            //	consistency with the current cursor, then add them into that condition set
            if (subResults.isEmpty())
            {
              subResults = inputResults;
            }
            else
            {
              Set<AntecedantCursor> validInputResults = new HashSet<>();

              for (AntecedantCursor cur : inputResults)
              {
                for (AntecedantCursor subResult : subResults)
                {
                  if (subResult.compatibleWith(cur))
                  {
                    AntecedantCursor combinedResult = new AntecedantCursor(subResult);
                    cur.unionInto(combinedResult);

                    validInputResults.add(combinedResult);
                  }
                }
              }

              subResults = validInputResults;
            }
          }
          else
          {
            //	This is a OR when viewed in the negative sense, so we just need one, and each such
            //	match is a new results set
            subResults.addAll(inputResults);
          }
        }
      }

      return subResults;
    }
    else if (p instanceof PolymorphicOr)
    {
      Set<AntecedantCursor> subResults = new HashSet<>();

      for (PolymorphicComponent c : p.getInputs())
      {
        if (subResults.size() > maxResultSet)
        {
          return null;
        }

        AntecedantCursor newCursor = new AntecedantCursor(cursor);
        Set<AntecedantCursor> inputResults = addPropositionAntecedants(pn,
                                                                       c,
                                                                       newCursor,
                                                                       maxResultSet,
                                                                       maxDepth,
                                                                       depth + 1);
        if (inputResults == null)
        {
          //	Any positive matches in an OR that requires a negative result => failure
          if (!cursor.isPositive)
          {
            return null;
          }
        }
        else
        {
          if (!cursor.isPositive)
          {
            //	We require ALL inputs to be negative, so take the conditions gathered for this one and validate
            //	consistency with the current cursor, then add them into that condition set
            if (subResults.isEmpty())
            {
              subResults = inputResults;
            }
            else
            {
              Set<AntecedantCursor> validInputResults = new HashSet<>();

              for (AntecedantCursor cur : inputResults)
              {
                for (AntecedantCursor subResult : subResults)
                {
                  if (subResult.compatibleWith(cur))
                  {
                    AntecedantCursor combinedResult = new AntecedantCursor(subResult);
                    cur.unionInto(combinedResult);

                    validInputResults.add(combinedResult);
                  }
                }
              }

              subResults = validInputResults;
            }
          }
          else
          {
            //	Any positive will do, and each such
            //	match is a new results set
            subResults.addAll(inputResults);
          }
        }
      }

      return subResults;
    }

    throw new RuntimeException("Unknon component");
  }

  public Set<MachineState> findSupportStates(GdlSentence queryProposition,
                                             int maxResultSet,
                                             int maxDepth)
  {
    Set<MachineState> result = new HashSet<>();

    PolymorphicProposition p = fullPropNet.findProposition(queryProposition);
    if (p != null)
    {
      Set<AntecedantCursor> cursorSet = addPropositionAntecedants(fullPropNet,
                                                                  p,
                                                                  new AntecedantCursor(),
                                                                  maxResultSet,
                                                                  maxDepth,
                                                                  0);

      for (AntecedantCursor c : cursorSet)
      {
        MachineState satisfyingState = new MachineState(new HashSet<GdlSentence>());

        for (PolymorphicProposition prop : c.positiveProps)
        {
          satisfyingState.getContents().add(prop.getName());
        }

        result.add(satisfyingState);
      }
    }

    return result;
  }

  public ForwardDeadReckonPropnetStateMachine()
  {
    this.maxInstances = 1;
  }

  public ForwardDeadReckonPropnetStateMachine(int maxInstances, long metagameTimeout)
  {
    this.maxInstances = maxInstances;
    this.metagameTimeout = metagameTimeout;
  }

  private ForwardDeadReckonPropnetStateMachine(ForwardDeadReckonPropnetStateMachine master, int instanceId)
  {
    this.maxInstances = -1;
    this.instanceId = instanceId;
    this.propNetX = master.propNetX;
    this.propNetO = master.propNetO;
    this.propNetXWithoutGoals = master.propNetXWithoutGoals;
    this.propNetOWithoutGoals = master.propNetOWithoutGoals;
    this.goalsNet = master.goalsNet;
    this.XSentence = master.XSentence;
    this.OSentence = master.OSentence;
    this.XSentenceInfo = master.XSentenceInfo;
    this.OSentenceInfo = master.OSentenceInfo;
    this.legalPropositionMovesX = master.legalPropositionMovesX;
    this.legalPropositionMovesO = master.legalPropositionMovesO;
    this.legalPropositionsX = master.legalPropositionsX;
    this.legalPropositionsO = master.legalPropositionsO;
    this.legalPropositions = master.legalPropositions;
    this.initialState = master.initialState;
    this.roles = master.roles;
    this.numRoles = master.numRoles;
    this.fullPropNet = master.fullPropNet;
    this.masterInfoSet = master.masterInfoSet;
    this.factors = master.factors;
    this.mPositiveGoalLatches = master.mPositiveGoalLatches;
    this.mNegativeGoalLatches = master.mNegativeGoalLatches;

    stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

    moveProps = new ForwardDeadReckonProposition[numRoles];
    chosenJointMoveProps = new ForwardDeadReckonProposition[numRoles];
    chosenMoves = new Move[numRoles];
    previouslyChosenJointMovePropsX = new ForwardDeadReckonProposition[numRoles];
    previouslyChosenJointMovePropsO = new ForwardDeadReckonProposition[numRoles];
    previouslyChosenJointMovePropIdsX = new int[numRoles];
    previouslyChosenJointMovePropIdsO = new int[numRoles];

    stats = new TestPropnetStateMachineStats(fullPropNet.getBasePropositions().size(),
                                             fullPropNet.getInputPropositions().size(),
                                             fullPropNet.getLegalPropositions().get(getRoles().get(0)).length);
  }

  public ForwardDeadReckonPropnetStateMachine createInstance()
  {
    if (numInstances >= maxInstances)
    {
      throw new RuntimeException("Too many instances");
    }

    ForwardDeadReckonPropnetStateMachine result = new ForwardDeadReckonPropnetStateMachine(this, numInstances++);

    return result;
  }

  /**
   * Initializes the PropNetStateMachine. You should compute the topological
   * ordering here. Additionally you may compute the initial state here, at
   * your discretion.
   */
  @Override
  public void initialize(List<Gdl> description)
  {
    long startTime = System.currentTimeMillis();

    setRandomSeed(1);

    try
    {
      //validationMachine = new ProverStateMachine();
      //validationMachine.initialize(description);

      fullPropNet = (ForwardDeadReckonPropNet)OptimizingPolymorphicPropNetFactory.create(
                                                                               description,
                                                                               new ForwardDeadReckonComponentFactory());
      fullPropNet.renderToFile("c:\\temp\\propnet_001.dot");

      OptimizingPolymorphicPropNetFactory.removeAnonymousPropositions(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnet_012_AnonRemoved.dot");
      LOGGER.debug("Num components after anon prop removal: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeUnreachableBasesAndInputs(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnet_014_UnreachablesRemoved.dot");

      OptimizingPolymorphicPropNetFactory.removeIrrelevantBasesAndInputs(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnet_016_IrrelevantRemoved.dot");
      LOGGER.debug("Num components after unreachable removal: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeRedundantConstantsAndGates(fullPropNet, false);
      fullPropNet.renderToFile("c:\\temp\\propnet_018_RedundantRemoved.dot");
      LOGGER.debug("Num components after first pass redundant components removal: " +
                   fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.refactorLargeGates(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnet_020_BeforeLargeFanout.dot");

      OptimizingPolymorphicPropNetFactory.refactorLargeFanouts(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnet_030_AfterLargeFanout.dot");
      LOGGER.debug("Num components after large gate refactoring: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeDuplicateLogic(fullPropNet);
      LOGGER.debug("Num components after duplicate removal: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.optimizeInputSets(fullPropNet);
      LOGGER.debug("Num components after input set optimization: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.OptimizeInvertedInputs(fullPropNet);
      LOGGER.debug("Num components after inverted input optimization: " + fullPropNet.getComponents().size());

      OptimizingPolymorphicPropNetFactory.removeRedundantConstantsAndGates(fullPropNet);
      LOGGER.debug("Num components after further removal of redundant components: " +
                   fullPropNet.getComponents().size());

      //  If we're using the fast animator we need to ensure that no propositions apart
      //  from strict input props (base, does, init) have any outputs, as this is assumed
      //  by the fast animator.  Accordingly we rewire slightly such that if any such do exist
      //  we replace their output connection by one from their input (which they anyway just
      //  directly forward, so this also removes a small propagation step)
      if ( ForwardDeadReckonPropNet.useFastAnimator )
      {
        OptimizingPolymorphicPropNetFactory.removeNonBaseOrDoesPropositionOutputs(fullPropNet);
      }

      fullPropNet.renderToFile("c:\\temp\\propnetReduced.dot");
      roles = fullPropNet.getRoles();
      numRoles = roles.size();

      moveProps = new ForwardDeadReckonProposition[numRoles];
      chosenJointMoveProps = new ForwardDeadReckonProposition[numRoles];
      chosenMoves = new Move[numRoles];
      previouslyChosenJointMovePropsX = new ForwardDeadReckonProposition[numRoles];
      previouslyChosenJointMovePropsO = new ForwardDeadReckonProposition[numRoles];
      previouslyChosenJointMovePropIdsX = new int[numRoles];
      previouslyChosenJointMovePropIdsO = new int[numRoles];
      stats = new TestPropnetStateMachineStats(fullPropNet.getBasePropositions().size(),
                                               fullPropNet.getInputPropositions().size(),
                                               fullPropNet.getLegalPropositions().get(getRoles().get(0)).length);
      //	Assess network statistics
      int numInputs = 0;
      int numMultiInputs = 0;
      int numMultiInputComponents = 0;

      for (PolymorphicComponent c : fullPropNet.getComponents())
      {
        int n = c.getInputs().size();

        numInputs += n;

        if (n > 1)
        {
          numMultiInputComponents++;
          numMultiInputs += n;
        }
      }

      int numComponents = fullPropNet.getComponents().size();
      LOGGER.debug("Num components: " + numComponents + " with an average of " + numInputs / numComponents + " inputs.");
      LOGGER.debug("Num multi-input components: " +
                   numMultiInputComponents +
                   " with an average of " +
                   (numMultiInputComponents == 0 ? "N/A" : numMultiInputs /
                                                           numMultiInputComponents) +
                   " inputs.");

      masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet.getBasePropositions().size()];
      int index = 0;
      for (Entry<GdlSentence, PolymorphicProposition> e : fullPropNet.getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition prop = (ForwardDeadReckonProposition)e.getValue();
        ForwardDeadReckonPropositionCrossReferenceInfo info = new ForwardDeadReckonPropositionCrossReferenceInfo();

        info.sentence = e.getKey();
        info.xNetProp = prop;
        info.oNetProp = prop;
        info.goalsNetProp = prop;
        info.index = index;

        masterInfoSet[index++] = info;

        prop.setInfo(info);
        basePropChangeCounts.put(info, 0);
      }

      //  Allow no more than half the remaining time for factorization analysis
      long factorizationAnalysisTimeout =  (metagameTimeout - System.currentTimeMillis())/2;
      if ( factorizationAnalysisTimeout > 0 )
      {
        FactorAnalyser factorAnalyser = new FactorAnalyser(this);
        factors = factorAnalyser.analyse(factorizationAnalysisTimeout);

        if ( factors != null )
        {
          LOGGER.info("Game appears factorize into " + factors.size() + " factors");
        }
      }

      fullPropNet.crystalize(masterInfoSet, maxInstances);

      for(ForwardDeadReckonPropositionInfo info : masterInfoSet)
      {
        ForwardDeadReckonPropositionCrossReferenceInfo crInfo = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

        crInfo.xNetPropId = crInfo.xNetProp.id;
        crInfo.oNetPropId = crInfo.oNetProp.id;
      }

      stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

      fullPropNet.reset(false);
      fullPropNet.setProposition(0, (ForwardDeadReckonProposition)fullPropNet.getInitProposition(), true);
      propNet = fullPropNet;
      initialState = getInternalStateFromBase().getMachineState();
      fullPropNet.reset(true);

      measuringBasePropChanges = true;

      try
      {
        for (int i = 0; i < 10; i++)
        {
          performDepthCharge(initialState, null);
        }
      }
      catch (TransitionDefinitionException e1)
      {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      catch (MoveDefinitionException e1)
      {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      measuringBasePropChanges = false;

      int highestCount = 0;
      for (Entry<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> e : basePropChangeCounts.entrySet())
      {
        if (e.getValue() > highestCount)
        {
          highestCount = e.getValue();
          XSentence = e.getKey().sentence;
        }
      }

      basePropChangeCounts = null;
      lastInternalSetState = null;
      lastGoalState = null;
      nextGoalState = null;
      propNet = null;

      propNetX = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
      propNetO = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
      goalsNet = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
      propNetX.RemoveInits();
      propNetO.RemoveInits();

      if (XSentence != null)
      {
        LOGGER.debug("Reducing with respect to XSentence: " + XSentence);
        OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, XSentence, true);

        //	If the reduced net always transitions it's own hard-wired sentence into the opposite state
        //	it may be part of a pivot whereby control passes between alternating propositions.  Check this
        //	Do we turn something else on unconditionally?
        for (Entry<GdlSentence, PolymorphicProposition> e : propNetX.getBasePropositions().entrySet())
        {
          PolymorphicComponent input = e.getValue().getSingleInput();

          if (input instanceof PolymorphicTransition)
          {
            PolymorphicComponent driver = input.getSingleInput();

            if (driver instanceof PolymorphicConstant && driver.getValue())
            {
              //	Found a suitable candidate
              OSentence = e.getKey();
              break;
            }
          }
        }

        if (OSentence != null)
        {
          LOGGER.debug("Possible OSentence: " + OSentence);
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, OSentence, true);

          //	Does this one turn the original back on?
          PolymorphicProposition originalPropInSecondNet = propNetO.getBasePropositions().get(XSentence);
          if (originalPropInSecondNet != null)
          {
            PolymorphicComponent input = originalPropInSecondNet.getSingleInput();

            if (input instanceof PolymorphicTransition)
            {
              PolymorphicComponent driver = input.getSingleInput();

              if (!(driver instanceof PolymorphicConstant) || !driver.getValue())
              {
                //	Nope - doesn't work
                OSentence = null;
                LOGGER.debug("Fails to recover back-transition to " + XSentence);
              }
            }
          }

          if (OSentence != null)
          {
            //	So if we set the first net's trigger condition to off in the second net do we find
            //	the second net's own trigger is always off?
            OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);

            PolymorphicProposition OSentenceInSecondNet = propNetO.getBasePropositions().get(OSentence);
            if (OSentenceInSecondNet != null)
            {
              PolymorphicComponent input = OSentenceInSecondNet.getSingleInput();

              if (input instanceof PolymorphicTransition)
              {
                PolymorphicComponent driver = input.getSingleInput();

                if (!(driver instanceof PolymorphicConstant) || driver.getValue())
                {
                  //	Nope - doesn't work
                  LOGGER.info("Fails to recover back-transition remove of " + OSentence);
                  OSentence = null;
                }

                //	Finally, if we set the OSentence off in the first net do we recover the fact that
                //	the XSentence always moves to off in transitions from the first net?
                if (OSentence != null)
                {
                  OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, OSentence, false);

                  PolymorphicProposition XSentenceInFirstNet = propNetX.getBasePropositions().get(XSentence);
                  if (XSentenceInFirstNet != null)
                  {
                    input = XSentenceInFirstNet.getSingleInput();

                    if (input instanceof PolymorphicTransition)
                    {
                      driver = input.getSingleInput();

                      if (!(driver instanceof PolymorphicConstant) ||
                          driver.getValue())
                      {
                        //	Nope - doesn't work
                        LOGGER.debug("Fails to recover removal of " + XSentence);
                        OSentence = null;
                      }
                    }
                  }
                }
              }
            }
            else
            {
              OSentence = null;
            }
          }
        }

        if (OSentence == null)
        {
          LOGGER.debug("Reverting OSentence optimizations");
          //	Failed - best we can do is simply drive the XSentence to true in one network
          propNetX = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
          propNetO = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
          propNetX.RemoveInits();
          propNetO.RemoveInits();
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, XSentence, true);
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
        }
        //OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
        propNetX.renderToFile("c:\\temp\\propnet_050_ReducedX.dot");
        propNetO.renderToFile("c:\\temp\\propnet_060_ReducedO.dot");
        LOGGER.debug("Num components remaining in X-net: " + propNetX.getComponents().size());
        LOGGER.debug("Num components remaining in O-net: " + propNetO.getComponents().size());
      }

      propNetXWithoutGoals = new ForwardDeadReckonPropNet(propNetX, new ForwardDeadReckonComponentFactory());
      propNetOWithoutGoals = new ForwardDeadReckonPropNet(propNetO, new ForwardDeadReckonComponentFactory());
      propNetXWithoutGoals.RemoveGoals();
      propNetOWithoutGoals.RemoveGoals();
      OptimizingPolymorphicPropNetFactory.minimizeNetwork(propNetXWithoutGoals);
      OptimizingPolymorphicPropNetFactory.minimizeNetwork(propNetOWithoutGoals);
      propNetXWithoutGoals.renderToFile("c:\\temp\\propnet_070_XWithoutGoals.dot");
      propNetOWithoutGoals.renderToFile("c:\\temp\\propnet_080_ROWithoutGoals.dot");
      LOGGER.info("Num components remaining in goal-less X-net: " + propNetXWithoutGoals.getComponents().size());
      LOGGER.info("Num components remaining in goal-less O-net: " + propNetOWithoutGoals.getComponents().size());

      goalsNet.RemoveAllButGoals();
      LOGGER.info("Goal net left with " + goalsNet.getComponents().size() + " components");
      goalsNet.renderToFile("c:\\temp\\propnet_090_GoalsReduced.dot");

      //masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet
      //    .getBasePropositions().size()];
      //index = 0;
      //	Cross-reference the base propositions of the two networks
      for (Entry<GdlSentence, PolymorphicProposition> e : fullPropNet.getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition oProp = (ForwardDeadReckonProposition)propNetO
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition xProp = (ForwardDeadReckonProposition)propNetX
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition goalsProp = (ForwardDeadReckonProposition)goalsNet
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition fullNetPropFdr = (ForwardDeadReckonProposition)e.getValue();
        ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fullNetPropFdr.getInfo();
        //ForwardDeadReckonPropositionCrossReferenceInfo info = new ForwardDeadReckonPropositionCrossReferenceInfo();

        //info.sentence = e.getKey();
        info.xNetProp = xProp;
        info.oNetProp = oProp;
        info.goalsNetProp = goalsProp;
        //info.index = index;

        //masterInfoSet[index++] = info;

        xProp.setInfo(info);
        oProp.setInfo(info);
        if (goalsProp != null)
        {
          goalsProp.setInfo(info);
        }

        if (e.getKey().equals(XSentence))
        {
          XSentenceInfo = info;
        }
        else if (e.getKey().equals(OSentence))
        {
          OSentenceInfo = info;
        }
      }

      propNetX.crystalize(masterInfoSet, maxInstances);
      propNetO.crystalize(masterInfoSet, maxInstances);
      goalsNet.crystalize(masterInfoSet, maxInstances);

      for(ForwardDeadReckonPropositionInfo info : masterInfoSet)
      {
        ForwardDeadReckonPropositionCrossReferenceInfo crInfo = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

        crInfo.xNetPropId = crInfo.xNetProp.id;
        crInfo.oNetPropId = crInfo.oNetProp.id;
      }

      goalsNet.reset(true);
      //	Force calculation of the goal set while we're single threaded
      goalsNet.getGoalPropositions();

      List<ForwardDeadReckonLegalMoveInfo> allMoves = new ArrayList<>();
      for (ForwardDeadReckonLegalMoveInfo info : propNetX.getMasterMoveList())
      {
        if (!allMoves.contains(info))
        {
          info.globalMoveIndex = allMoves.size();
          allMoves.add(info);
        }
      }
      for (ForwardDeadReckonLegalMoveInfo info : propNetO.getMasterMoveList())
      {
        if (!allMoves.contains(info))
        {
          info.globalMoveIndex = allMoves.size();
          allMoves.add(info);
        }
      }

      setupAllMovesArrayFromList(allMoves);

      stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

      propNetX.reset(true);
      propNetO.reset(true);
      //	Force calculation of the goal set while we're single threaded
      propNetX.getGoalPropositions();
      propNetO.getGoalPropositions();

      propNet = propNetX;
      legalPropositions = legalPropositionsX;
    }
    catch (InterruptedException e)
    {
      // TODO: handle exception
    }
  }

  public void Optimize()
  {
    for (PolymorphicComponent c : propNet.getComponents())
    {
      ((LearningComponent)c).Optimize();
    }
  }

  public ForwardDeadReckonPropNet getFullPropNet()
  {
    return fullPropNet;
  }

  private void setBasePropositionsFromState(MachineState state)
  {
    setBasePropositionsFromState(createInternalState(masterInfoSet,
                                                     XSentence,
                                                     state),
                                 null,
                                 false);
  }

  private ForwardDeadReckonInternalMachineState stateBufferX1 = null;
  private ForwardDeadReckonInternalMachineState stateBufferX2 = null;
  private ForwardDeadReckonInternalMachineState stateBufferO1 = null;
  private ForwardDeadReckonInternalMachineState stateBufferO2 = null;

  private void setBasePropositionsFromState(ForwardDeadReckonInternalMachineState state,
                                            Factor factor,
                                            boolean isolate)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.setBasePropositionsInternal");
    try
    {
      //System.out.println("Set state for instance " + instanceId + ": " + state);
      //System.out.println("Last set state for instance " + instanceId + " was: " + lastInternalSetState);
      if (lastInternalSetState != null)
      {
        if (!lastInternalSetState.equals(state))
        {
          ForwardDeadReckonInternalMachineState nextInternalSetState;

          ProfileSection stateManipulationSection = ProfileSection.newInstance("TestPropnetStateMachine.manipulateStateDiff");
          try
          {
            lastInternalSetState.xor(state);
            if (isolate)
            {
              if (propNet == propNetX)
              {
                nextInternalSetState = (lastInternalSetState == stateBufferX1 ? stateBufferX2
                                                                             : stateBufferX1);
              }
              else
              {
                nextInternalSetState = (lastInternalSetState == stateBufferO1 ? stateBufferO2
                                                                             : stateBufferO1);
              }
              //nextInternalSetState = new ForwardDeadReckonInternalMachineState(state);
              nextInternalSetState.copy(state);
            }
            else
            {
              nextInternalSetState = state;
            }
          }
          finally
          {
            stateManipulationSection.exitScope();
          }

          ForwardDeadReckonPropnetFastAnimator.InstanceInfo instanceInfo;

          if ( ForwardDeadReckonPropNet.useFastAnimator && !measuringBasePropChanges )
          {
            instanceInfo = propNet.animator.getInstanceInfo(instanceId);

            if ( propNet == propNetX)
            {
              for (ForwardDeadReckonPropositionInfo info : lastInternalSetState)
              {
                ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

                propNet.animator.changeComponentValueTo(instanceInfo, infoCr.xNetProp.id, nextInternalSetState.contains(info));
              }
            }
            else
            {
              for (ForwardDeadReckonPropositionInfo info : lastInternalSetState)
              {
                ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

                propNet.animator.changeComponentValueTo(instanceInfo, infoCr.oNetProp.id, nextInternalSetState.contains(info));
              }
            }
          }
          else
          {
            for (ForwardDeadReckonPropositionInfo info : lastInternalSetState)
            {
              ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

              //if ( factor == null || factor.getStateMask().contains(infoCr))
              {
                if (nextInternalSetState.contains(info))
                {
                  ProfileSection setPropsSection = ProfileSection.newInstance("TestPropnetStateMachine.setNewProps");
                  try
                  {
                    if (propNet == propNetX)
                    {
                      if (ForwardDeadReckonPropNet.useFastAnimator)
                      {
                        propNet.animator.setComponentValue(instanceId, infoCr.xNetProp.id, true);
                      }
                      else
                      {
                        infoCr.xNetProp.setValue(true, instanceId);
                      }
                    }
                    else
                    {
                      if (ForwardDeadReckonPropNet.useFastAnimator)
                      {
                        propNet.animator.setComponentValue(instanceId, infoCr.oNetProp.id, true);
                      }
                      else
                      {
                        infoCr.oNetProp.setValue(true, instanceId);
                      }
                    }
                  }
                  finally
                  {
                    setPropsSection.exitScope();
                  }
                }
                else
                {
                  ProfileSection clearPropsSection = ProfileSection.newInstance("TestPropnetStateMachine.clearOldProps");
                  try
                  {
                    if (propNet == propNetX)
                    {
                      if (ForwardDeadReckonPropNet.useFastAnimator)
                      {
                        propNet.animator.setComponentValue(instanceId, infoCr.xNetProp.id, false);
                      }
                      else
                      {
                        infoCr.xNetProp.setValue(false, instanceId);
                      }
                    }
                    else
                    {
                      if (ForwardDeadReckonPropNet.useFastAnimator)
                      {
                        propNet.animator.setComponentValue(instanceId, infoCr.oNetProp.id, false);
                      }
                      else
                      {
                        infoCr.oNetProp.setValue(false, instanceId);
                      }
                    }
                  }
                  finally
                  {
                    clearPropsSection.exitScope();
                  }
                }
              }

              if (measuringBasePropChanges)
              {
                basePropChangeCounts.put(infoCr,
                                         basePropChangeCounts.get(infoCr) + 1);
              }
            }
          }

          lastInternalSetState = nextInternalSetState;
        }
      }
      else
      {
        if (isolate)
        {
          lastInternalSetState = new ForwardDeadReckonInternalMachineState(state);
          //System.out.println("Created isolated last set state: " + lastInternalSetState);
        }
        else
        {
          lastInternalSetState = state;
          //System.out.println("Copying to last set state: " + lastInternalSetState);
        }

        ForwardDeadReckonPropnetFastAnimator.InstanceInfo instanceInfo;

        if (ForwardDeadReckonPropNet.useFastAnimator)
        {
          instanceInfo = propNet.animator.getInstanceInfo(instanceId);
        }

        //System.out.println("Setting entire state");
        for (PolymorphicProposition p : propNet.getBasePropositionsArray())
        {
          if (ForwardDeadReckonPropNet.useFastAnimator)
          {
            propNet.animator.setComponentValue(instanceId, ((ForwardDeadReckonProposition)p).id, false);
          }
          else
          {
            ((ForwardDeadReckonProposition)p).setValue(false, instanceId);
          }
        }
        for (ForwardDeadReckonPropositionInfo s : state)
        {
          ForwardDeadReckonPropositionCrossReferenceInfo sCr = (ForwardDeadReckonPropositionCrossReferenceInfo)s;
          if (propNet == propNetX)
          {
            if (ForwardDeadReckonPropNet.useFastAnimator)
            {
              propNet.animator.changeComponentValueTo(instanceInfo, sCr.xNetProp.id, true);
            }
            else
            {
              sCr.xNetProp.setValue(true, instanceId);
            }
          }
          else
          {
            if (ForwardDeadReckonPropNet.useFastAnimator)
            {
              propNet.animator.changeComponentValueTo(instanceInfo, sCr.oNetProp.id, true);
            }
            else
            {
              sCr.oNetProp.setValue(true, instanceId);
            }
          }
        }
      }
    }
    finally
    {
    	methodSection.exitScope();
    }
  }

  /**
   * Computes if the state is terminal. Should return the value of the terminal
   * proposition for the state.
   */
  @Override
  public boolean isTerminal(MachineState state)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.isTerminal");
    try
    {
      setPropNetUsage(state);
      setBasePropositionsFromState(state);

      PolymorphicProposition terminalProp = propNet.getTerminalProposition();
      //boolean result = propNet.getTransition(instanceId, (ForwardDeadReckonComponent)terminalProp.getSingleInput());
      boolean result = propNet.getComponentValue(instanceId, (ForwardDeadReckonComponent)terminalProp);
      //boolean result = ((ForwardDeadReckonComponent)terminalProp
      //    .getSingleInput()).getValue(instanceId);
      //if ( result )
      //{
      //	System.out.println("State " + state + " is terminal");
      //}

      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public boolean isTerminal(ForwardDeadReckonInternalMachineState state)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.isTerminalState");
    try
    {
      setPropNetUsage(state);
      setBasePropositionsFromState(state, null, true);

      return isTerminal();
    }
    finally
    {
    	methodSection.exitScope();
    }
  }

  public boolean isTerminal()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.isTerminal");
    try
    {
      if ( factors != null && !hasAvailableMoveForAllRoles() )
      {
        return true;
      }

      return isTerminalUnfactored();
    }
    finally
    {
    	methodSection.exitScope();
    }
  }

  private boolean isTerminalUnfactored()
  {
    PolymorphicProposition terminalProp = propNet.getTerminalProposition();
    boolean result = propNet.getComponentValue(instanceId, (ForwardDeadReckonComponent)terminalProp);
    //boolean result = ((ForwardDeadReckonComponent)terminalProp
    //    .getSingleInput()).getValue(instanceId);
    //if ( result )
    //{
    //  System.out.println("State " + state + " is terminal");
    //}

    if (validationMachine != null)
    {
      if (validationMachine.isTerminal(validationState) != result)
      {
        LOGGER.warn("Terminality mismatch");
      }
    }
    return result;
  }

  /**
   * Computes the goal for a role in the current state. Should return the value
   * of the goal proposition that is true for that role. If there is not
   * exactly one goal proposition true for that role, then you should throw a
   * GoalDefinitionException because the goal is ill-defined.
   */
  @Override
  public int getGoal(MachineState state, Role role)
  {
    ForwardDeadReckonInternalMachineState internalState = createInternalState(state);
    return getGoal(internalState, role);
  }

  public int getGoal(Role role)
  {
    return getGoal((ForwardDeadReckonInternalMachineState)null, role);
  }

  /**
   * Returns the initial state. The initial state can be computed by only
   * setting the truth value of the INIT proposition to true, and then
   * computing the resulting state.
   */
  @Override
  public MachineState getInitialState()
  {
    LOGGER.trace("Initial state: " + initialState);
    return initialState;
  }

  /**
   * Computes the legal moves for role in state.
   */
  @Override
  public List<Move> getLegalMoves(MachineState state, Role role)
      throws MoveDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getLegalMoves");
    try
    {
      List<Move> result;

      setPropNetUsage(state);
      setBasePropositionsFromState(state);

      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;

      result = new LinkedList<>();
      for (ForwardDeadReckonLegalMoveInfo moveInfo : propNet
          .getActiveLegalProps(instanceId).getContents(role))
      {
        result.add(moveInfo.move);
      }

      //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
      //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

      //System.out.println("legals for role " + role + ": " + result);
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public List<Move> getLegalMoves(ForwardDeadReckonInternalMachineState state,
                                  Role role) throws MoveDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getLegalMoves");
    try
    {
      List<Move> result;

      setPropNetUsage(state);
      setBasePropositionsFromState(state, null, true);

      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;

      result = new LinkedList<>();
      for (ForwardDeadReckonLegalMoveInfo moveInfo : propNet.getActiveLegalProps(instanceId).getContents(role))
      {
        result.add(moveInfo.move);
      }

      //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
      //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

      //System.out.println("Legality in state: " + state);
      //System.out.println("legals for role " + role + ": " + result);
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public Collection<ForwardDeadReckonLegalMoveInfo> getLegalMoves(ForwardDeadReckonInternalMachineState state,
                                                                  Role role,
                                                                  Factor factor)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getLegalMoveInfos");
    try
    {
      setPropNetUsage(state);
      setBasePropositionsFromState(state, factor, true);

      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;
      Collection<ForwardDeadReckonLegalMoveInfo> baseIterable =
                                                              propNet.getActiveLegalProps(instanceId).getContents(role);
      if ( factor == null )
      {
        return baseIterable;
      }
      return new FactorFilteredForwardDeadReckonLegalMoveSet(factor, baseIterable, true);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * Checks whether a specified move is legal for role in state.
   */
  public boolean isLegalMove(MachineState state, Role role, Move move)
      throws MoveDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.isLegalMove");
    try
    {
      setPropNetUsage(state);
      setBasePropositionsFromState(state);

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet
          .getInputPropositions();

      GdlSentence moveSentence = ProverQueryBuilder.toDoes(role, move);
      PolymorphicProposition moveInputProposition = inputProps
          .get(moveSentence);
      PolymorphicProposition legalProp = propNet.getLegalInputMap()
          .get(moveInputProposition);
      if (legalProp != null)
      {
        return ((ForwardDeadReckonComponent)legalProp.getSingleInput())
            .getValue(instanceId);
      }

      throw new MoveDefinitionException(state, role);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private void setPropNetUsage(MachineState state)
  {
    setPropNetUsage(createInternalState(masterInfoSet, XSentence, state));
  }

  private void setPropNetUsage(ForwardDeadReckonInternalMachineState state)
  {
    //System.out.println("setPropNetUsage for state: " + state);
    if (XSentence != null)
    {
      if (state.isXState)
      {
        if (propNet != propNetX)
        {
          //System.out.println("Switching (internal) to machine X in state: " + state);
          propNet = propNetX;
          legalPropositions = legalPropositionsX;

          lastInternalSetStateO = lastInternalSetState;
          lastInternalSetState = lastInternalSetStateX;
        }
      }
      else
      {
        if (propNet != propNetO)
        {
          //System.out.println("Switching (internal) to machine O in state: " + state);
          propNet = propNetO;
          legalPropositions = legalPropositionsO;

          lastInternalSetStateX = lastInternalSetState;
          lastInternalSetState = lastInternalSetStateO;
        }
      }
    }
  }

  /**
   * Computes the next state given state and the list of moves.
   */
  @Override
  public MachineState getNextState(MachineState state, List<Move> moves)
      throws TransitionDefinitionException
  {
    //System.out.println("Get next state after " + moves + " from: " + state);
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getNextState");
    try
    {
      setPropNetUsage(state);
      //for(PolymorphicComponent c : propNet.getComponents())
      //{
      //	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
      //}
      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet.getInputPropositions();
      int movesCount = 0;

      for (GdlSentence moveSentence : toDoes(moves))
      {
        ForwardDeadReckonProposition moveInputProposition = (ForwardDeadReckonProposition)inputProps
            .get(moveSentence);
        if (moveInputProposition != null)
        {
          propNet.setProposition(instanceId, moveInputProposition, true);
          //moveInputProposition.setValue(true, instanceId);
          moveProps[movesCount++] = moveInputProposition;
        }
      }

      setBasePropositionsFromState(state);

      MachineState result = getInternalStateFromBase().getMachineState();

      //System.out.println("After move " + moves + " in state " + state + " resulting state is " + result);
      //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
      //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

      for (int i = 0; i < movesCount; i++)
      {
        propNet.setProposition(instanceId, moveProps[i], false);
        //moveProps[i].setValue(false, instanceId);
      }
      //for(GdlSentence moveSentence : toDoes(moves))
      //{
      //	PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
      //	if ( moveInputProposition != null )
      //	{
      //		moveInputProposition.setValue(false);
      //	}
      //}

      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public ForwardDeadReckonInternalMachineState getNextState(ForwardDeadReckonInternalMachineState state,
                                                            List<Move> moves)
      throws TransitionDefinitionException
  {
    //System.out.println("Get next state after " + moves + " from: " + state);
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getNextState");
    try
    {
      setPropNetUsage(state);
      //for(PolymorphicComponent c : propNet.getComponents())
      //{
      //	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
      //}
      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet
          .getInputPropositions();
      int movesCount = 0;

      for (GdlSentence moveSentence : toDoes(moves))
      {
        ForwardDeadReckonProposition moveInputProposition = (ForwardDeadReckonProposition)inputProps
            .get(moveSentence);
        if (moveInputProposition != null)
        {
          propNet.setProposition(instanceId, moveInputProposition, true);
          //moveInputProposition.setValue(true, instanceId);
          moveProps[movesCount++] = moveInputProposition;
        }
      }

      ForwardDeadReckonInternalMachineState result;

      if ( movesCount > 0 )
      {
        setBasePropositionsFromState(state, null, true);

        result = getInternalStateFromBase();

        //System.out.println("After move " + moves + " in state " + state + " resulting state is " + result);
        //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
        //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

        for (int i = 0; i < movesCount; i++)
        {
          propNet.setProposition(instanceId, moveProps[i], false);
          //moveProps[i].setValue(false, instanceId);
        }
      }
      else
      {
        result = state;
      }
      //for(GdlSentence moveSentence : toDoes(moves))
      //{
      //	PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
      //	if ( moveInputProposition != null )
      //	{
      //		moveInputProposition.setValue(false);
      //	}
      //}
      //System.out.println("Return state " + result + " with hash " + result.hashCode());
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public ForwardDeadReckonInternalMachineState getNextState(ForwardDeadReckonInternalMachineState state,
                                                            Move[] moves)
      throws TransitionDefinitionException
  {
    //System.out.println("Get next state after " + moves + " from: " + state);
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getNextState");
    try
    {
      setPropNetUsage(state);
      //for(PolymorphicComponent c : propNet.getComponents())
      //{
      //	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
      //}
      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet.getInputPropositions();
      int movesCount = 0;

      for (GdlSentence moveSentence : toDoes(moves))
      {
        ForwardDeadReckonProposition moveInputProposition = (ForwardDeadReckonProposition)inputProps
            .get(moveSentence);
        if (moveInputProposition != null)
        {
          propNet.setProposition(instanceId, moveInputProposition, true);
          //moveInputProposition.setValue(true, instanceId);
          moveProps[movesCount++] = moveInputProposition;
        }
      }

      setBasePropositionsFromState(state, null, true);

      ForwardDeadReckonInternalMachineState result = getInternalStateFromBase();

      //System.out.println("After move " + moves + " in state " + state + " resulting state is " + result);
      //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
      //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

      for (int i = 0; i < movesCount; i++)
      {
        propNet.setProposition(instanceId, moveProps[i], false);
        //moveProps[i].setValue(false, instanceId);
      }
      //for(GdlSentence moveSentence : toDoes(moves))
      //{
      //	PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
      //	if ( moveInputProposition != null )
      //	{
      //		moveInputProposition.setValue(false);
      //	}
      //}
      //System.out.println("Return state " + result + " with hash " + result.hashCode());
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public ForwardDeadReckonInternalMachineState getNextState(ForwardDeadReckonInternalMachineState state,
                                                            Factor factor,
                                                            ForwardDeadReckonLegalMoveInfo[] moves)
      throws TransitionDefinitionException
  {
    //System.out.println("Get next state after " + moves + " from: " + state);
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getNextState");
    try
    {
      ForwardDeadReckonInternalMachineState result;

      setPropNetUsage(state);

      int movesCount = 0;

      for (ForwardDeadReckonLegalMoveInfo move : moves)
      {
        if ( !move.isPseudoNoOp)
        {
          ForwardDeadReckonProposition moveInputProposition = move.inputProposition;
          if (moveInputProposition != null)
          {
            propNet.setProposition(instanceId, moveInputProposition, true);
            //moveInputProposition.setValue(true, instanceId);
            moveProps[movesCount++] = moveInputProposition;
          }
        }
      }

      setBasePropositionsFromState(state, factor, true);

      result = getInternalStateFromBase();

      //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
      //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

      for (int i = 0; i < movesCount; i++)
      {
        propNet.setProposition(instanceId, moveProps[i], false);
        //moveProps[i].setValue(false, instanceId);
      }

      if ( movesCount == 0 && factor != null )
      {
        //  Hack - re-impose the base props from the starting state.  We need to do it this
        //  way in order for the non-factor turn logic (control prop, step, etc) to generate
        //  correctly, but then make sure we have not changed any factor-specific base props
        //  which can happen because no moves were played (consider distinct clauses on moves)
        ForwardDeadReckonInternalMachineState basePropState = new ForwardDeadReckonInternalMachineState(state);

        basePropState.intersect(factor.getStateMask(true));
        result.intersect(factor.getInverseStateMask(true));
        result.merge(basePropState);
      }

      //System.out.println("After move " + moves + " in state " + state + " resulting state is " + result);
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private boolean transitionToNextStateFromChosenMove(Role choosingRole,
                                                      List<TerminalResultVector> resultVectors)
      throws GoalDefinitionException
  {
    //System.out.println("Get next state after " + moves + " from: " + state);
    //RuntimeOptimizedComponent.getCount = 0;
    //RuntimeOptimizedComponent.dirtyCount = 0;
    ProfileSection methodSection =
                              ProfileSection.newInstance("TestPropnetStateMachine.transitionToNextStateFromChosenMove");
    try
    {
      //for(PolymorphicComponent c : propNet.getComponents())
      //{
      //	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
      //}
      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;
      if (validationMachine != null)
      {
        List<Move> moves = new LinkedList<>();

        for (Move move : chosenMoves)
        {
          moves.add(move);
        }
        try
        {
          validationMachine.getNextState(validationState, moves);
        }
        catch (TransitionDefinitionException e)
        {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }

      //System.out.println("Pre-transition state: " + currentState);
      //try {
      //	System.out.println("Goal value before transition: " + getGoal(getRoles().get(0)));
      //} catch (GoalDefinitionException e) {
      // TODO Auto-generated catch block
      //	e.printStackTrace();
      //}

      int index = 0;
      for (ForwardDeadReckonProposition moveProp : chosenJointMoveProps)
      {
        ForwardDeadReckonProposition previousChosenMove;
        int previousChosenMoveId;

        if (ForwardDeadReckonPropNet.useFastAnimator)
        {
          if (propNet == propNetX)
          {
            previousChosenMoveId = previouslyChosenJointMovePropIdsX[index];
          }
          else
          {
            previousChosenMoveId = previouslyChosenJointMovePropIdsO[index];
          }
        }
        else
        {
          if (propNet == propNetX)
          {
            previousChosenMove = previouslyChosenJointMovePropsX[index];
          }
          else
          {
            previousChosenMove = previouslyChosenJointMovePropsO[index];
          }
        }

        int movePropId;
        if (ForwardDeadReckonPropNet.useFastAnimator)
        {
          if (moveProp != null)
          {
            movePropId = moveProp.id;
            propNet.animator.setComponentValue(instanceId, movePropId, true);
          }
          else
          {
            movePropId = -1;
          }
        }
        else
        {
          if (moveProp != null)
          {
            propNet.setProposition(instanceId, moveProp, true);
            //moveProp.setValue(true, instanceId);
            //System.out.println("Move: " + moveProp.getName());
          }
        }
        if (ForwardDeadReckonPropNet.useFastAnimator)
        {
          if ( previousChosenMoveId != -1 && previousChosenMoveId != movePropId )
          {
            propNet.animator.setComponentValue(instanceId, previousChosenMoveId, false);
          }
          if (propNet == propNetX)
          {
            previouslyChosenJointMovePropIdsX[index++] = movePropId;
          }
          else
          {
            previouslyChosenJointMovePropIdsO[index++] = movePropId;
          }
        }
        else
        {
          if (previousChosenMove != null && previousChosenMove != moveProp)
          {
            previousChosenMove.setValue(false, instanceId);
          }
          if (propNet == propNetX)
          {
            previouslyChosenJointMovePropsX[index++] = moveProp;
          }
          else
          {
            previouslyChosenJointMovePropsO[index++] = moveProp;
          }
        }
      }

      propagateCalculatedNextState();

      return true;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /* Already implemented for you */
  @Override
  public List<Role> getRoles()
  {
    return roles;
  }

  /* Helper methods */

  /**
   * The Input propositions are indexed by (does ?player ?action). This
   * translates a list of Moves (backed by a sentence that is simply ?action)
   * into GdlSentences that can be used to get Propositions from
   * inputPropositions. and accordingly set their values etc. This is a naive
   * implementation when coupled with setting input values, feel free to change
   * this for a more efficient implementation.
   *
   * @param moves
   * @return
   */
  private List<GdlSentence> toDoes(Move[] moves)
  {
    List<GdlSentence> doeses = new ArrayList<>(moves.length);
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (int i = 0; i < numRoles; i++)
    {
      int index = roleIndices.get(roles.get(i));
      doeses.add(ProverQueryBuilder.toDoes(roles.get(i), moves[index]));
    }
    return doeses;
  }

  /**
   * The Input propositions are indexed by (does ?player ?action). This
   * translates a list of Moves (backed by a sentence that is simply ?action)
   * into GdlSentences that can be used to get Propositions from
   * inputPropositions. and accordingly set their values etc. This is a naive
   * implementation when coupled with setting input values, feel free to change
   * this for a more efficient implementation.
   *
   * @param moves
   * @return
   */
  private List<GdlSentence> toDoes(List<Move> moves)
  {
    List<GdlSentence> doeses = new ArrayList<>(moves.size());
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (int i = 0; i < numRoles; i++)
    {
      int index = roleIndices.get(roles.get(i));
      doeses.add(ProverQueryBuilder.toDoes(roles.get(i), moves.get(index)));
    }
    return doeses;
  }

  private void propagateCalculatedNextState()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.propagateCalculatedNextState");
    try
    {
      ForwardDeadReckonInternalMachineState transitionTo = propNet
          .getActiveBaseProps(instanceId);

      boolean targetIsXNet = transitionTo.contains(XSentenceInfo);
      if (propNet == propNetX)
      {
        if (!targetIsXNet)
        {
          //System.out.println("Switching to ONet");
          propNet = propNetO;
          lastInternalSetStateX = lastInternalSetState;
          lastInternalSetState = lastInternalSetStateO;

          legalPropositions = legalPropositionsO;
        }
      }
      else
      {
        if (targetIsXNet)
        {
          //System.out.println("Switching to XNet");
          propNet = propNetX;
          lastInternalSetStateO = lastInternalSetState;
          lastInternalSetState = lastInternalSetStateX;

          legalPropositions = legalPropositionsX;
        }
      }

      transitionTo.isXState = targetIsXNet;

      //System.out.println("Transitioning to: " + transitionTo);

      setBasePropositionsFromState(transitionTo, null, true);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private ForwardDeadReckonInternalMachineState getInternalStateFromBase()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getStateFromBase");
    try
    {
      //RuntimeOptimizedComponent.getCount = 0;

      ForwardDeadReckonInternalMachineState result = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      for (ForwardDeadReckonPropositionInfo info : propNet.getActiveBaseProps(instanceId))
      {
        result.add(info);

        if (info.sentence == XSentence)
        {
          result.isXState = true;
        }
      }

      return result;
      //Set<GdlSentence> contents = new HashSet<GdlSentence>();
      //nt numBaseProps = basePropositionTransitions.length;

      //for (int i = 0; i < numBaseProps; i++)
      //{
      //	PolymorphicComponent t = basePropositionTransitions[i];
      //	if (t.getValue())
      //	{
      //		contents.add(propNet.getBasePropositionsArray()[i].getName());
      //	}
      //}
      //stats.addGetCount(RuntimeOptimizedComponent.getCount);

      //return new MachineState(contents);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private Map<Role, List<Move>> recentLegalMoveSetsList = new HashMap<>();

  @Override
  public Move getRandomMove(MachineState state, Role role)
      throws MoveDefinitionException
  {
    if (useSampleOfKnownLegals)
    {
      int choiceSeed = getRandom(100);
      final int tryPreviousPercentage = 80;
      List<Move> previouslyAvailableMoves = null;
      boolean preferNew = false;

      if (choiceSeed < tryPreviousPercentage &&
          recentLegalMoveSetsList.keySet().contains(role))
      {
        previouslyAvailableMoves = recentLegalMoveSetsList.get(role);
        Move result = previouslyAvailableMoves
            .get(getRandom(previouslyAvailableMoves.size()));

        if (isLegalMove(state, role, result))
        {
          return result;
        }
      }
      else if (choiceSeed > 100 - tryPreviousPercentage / 2)
      {
        preferNew = true;
      }

      List<Move> legals = getLegalMoves(state, role);
      List<Move> candidates;

      if (preferNew && previouslyAvailableMoves != null)
      {
        candidates = new LinkedList<>();

        for (Move move : legals)
        {
          if (!previouslyAvailableMoves.contains(move))
          {
            candidates.add(move);
          }
        }
      }
      else
      {
        candidates = legals;
      }

      if (legals.size() > 1)
      {
        recentLegalMoveSetsList.put(role, legals);
      }

      return candidates.get(getRandom(candidates.size()));
    }
    List<Move> legals = getLegalMoves(state, role);

    int randIndex = getRandom(legals.size());
    return legals.get(randIndex);
  }

  private class RolloutDecisionState
  {
    public ForwardDeadReckonLegalMoveInfo[] chooserMoves;
    public ForwardDeadReckonProposition[]   nonChooserProps;
    public int                              chooserIndex;
    public int                              baseChoiceIndex;
    public int                              nextChoiceIndex;
    public int                              rolloutSeq;
    ForwardDeadReckonInternalMachineState   state;
    Role                                    choosingRole;
    public boolean[]                        propProcessed;
  }

  private class TerminalResultSet
  {
    public TerminalResultSet(List<Role> roles)
    {
      resultVector = null;
    }

    public void considerResult(Role choosingRole)
    {
      TerminalResultVector newResultVector = new TerminalResultVector(choosingRole);

      for (Role role : getRoles())
      {
        newResultVector.scores.put(role, new Integer(getGoal(role)));
      }

      if (resultVector == null ||
          newResultVector.scores.get(resultVector.controllingRole) > resultVector.scores
              .get(resultVector.controllingRole))
      {
        //	Would this one be chosen over the previous
        resultVector = newResultVector;
        resultVector.state = new ForwardDeadReckonInternalMachineState(lastInternalSetState);
      }
    }

    public TerminalResultVector resultVector;
  }

  private final int              maxGameLength        = 500;                                    //	C4 larger up to 400 is largest seen
  private RolloutDecisionState[] rolloutDecisionStack = new RolloutDecisionState[maxGameLength];
  private int                    rolloutStackDepth;
  private int                    rolloutSeq           = 0;

  private int                    totalRoleoutChoices;
  private int                    totalRoleoutNodesExamined;

  private void doRecursiveGreedyRoleout(TerminalResultSet results,
                                        Factor factor,
                                        MoveWeights moveWeights,
                                        List<ForwardDeadReckonLegalMoveInfo> playedMoves)
      throws MoveDefinitionException, GoalDefinitionException
  {
    //		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.doRecursiveGreedyRoleout");
    //		try
    //		{
    ForwardDeadReckonProposition hintMoveProp = null;
    int hintMoveDepth = -1;

    do
    {
      assert(playedMoves == null || playedMoves.size() == rolloutStackDepth);
      ForwardDeadReckonProposition winningMoveProp = transitionToNextStateInGreedyRollout(results,
                                                                                          factor,
                                                                                          hintMoveProp,
                                                                                          moveWeights,
                                                                                          playedMoves);
      if (winningMoveProp != null)
      {
        hintMoveProp = winningMoveProp;
        hintMoveDepth = rolloutStackDepth;

        //	Next player had a 1 move forced win.  Pop the stack and choose again at this level unless deciding player was
        //	the same as for this node
        //	TODO - this doesn't handle well games in which the same player gets to play multiple times
        if (rolloutStackDepth > 0 &&
            rolloutDecisionStack[rolloutStackDepth - 1].nextChoiceIndex != rolloutDecisionStack[rolloutStackDepth - 1].baseChoiceIndex)
        {
          if (rolloutDecisionStack[rolloutStackDepth].chooserIndex != rolloutDecisionStack[rolloutStackDepth - 1].chooserIndex)
          {
            //System.out.println("Forced win at depth " + rolloutStackDepth);
            rolloutDecisionStack[rolloutStackDepth].chooserMoves = null;

            RolloutDecisionState poppedState = rolloutDecisionStack[--rolloutStackDepth];
            if (playedMoves != null)
            {
              //System.out.println("Pop move after avoidable loss");
              playedMoves.remove(playedMoves.size() - 1);
            }
            //System.out.println("...next choice=" + poppedState.nextChoiceIndex + " (base was " + poppedState.baseChoiceIndex + ")");

            setPropNetUsage(poppedState.state);
            setBasePropositionsFromState(poppedState.state, factor, true);
          }
          else
          {
            results
                .considerResult(rolloutDecisionStack[rolloutStackDepth].choosingRole);
            break;
          }
        }
        else
        {
          results
              .considerResult(rolloutDecisionStack[rolloutStackDepth].choosingRole);
          break;
        }
      }
      else if (!isTerminal())
      {
        if (rolloutStackDepth++ >= hintMoveDepth)
        {
          hintMoveProp = null;
        }
        //System.out.println("Non-terminal, advance to depth " + rolloutStackDepth);
      }
      else if (rolloutDecisionStack[rolloutStackDepth].nextChoiceIndex != rolloutDecisionStack[rolloutStackDepth].baseChoiceIndex)
      {
        //System.out.println("Try another choice after finding terminal, next=" + rolloutDecisionStack[rolloutStackDepth].nextChoiceIndex);
        //	Having recorded the potential terminal state continue to explore another
        //	branch given that this terminality was not a forced win for the deciding player
        RolloutDecisionState decisionState = rolloutDecisionStack[rolloutStackDepth];

        if (playedMoves != null)
        {
          //System.out.println("Pop move after encountering loss");
          playedMoves.remove(playedMoves.size() - 1);
        }

        setPropNetUsage(decisionState.state);
        setBasePropositionsFromState(decisionState.state, null, true);
      }
      else
      {
        break;
      }
    }
    while (true);
    //		}
    //		finally
    //		{
    //			methodSection.exitScope();
    //		}
  }

  private double recursiveGreedyRollout(TerminalResultSet results,
                                        Factor factor,
                                        MoveWeights moveWeights,
                                        List<ForwardDeadReckonLegalMoveInfo> playedMoves)
      throws MoveDefinitionException, GoalDefinitionException
  {
    rolloutSeq++;
    rolloutStackDepth = 0;
    totalRoleoutChoices = 0;
    totalRoleoutNodesExamined = 0;

    doRecursiveGreedyRoleout(results, factor, moveWeights, playedMoves);

    if (totalRoleoutNodesExamined > 0)
    {
      return totalRoleoutChoices / totalRoleoutNodesExamined;
    }
    return 0;
  }

  private String mungedState(ForwardDeadReckonInternalMachineState state)
  {
    StringBuilder sb = new StringBuilder();

    for (GdlSentence s : state.getMachineState().getContents())
    {
      if (s.toString().contains("wn"))
      {
        sb.append(s.toString());
      }
    }

    return sb.toString();
  }

  private Set<ForwardDeadReckonProposition> terminatingMoveProps             = new HashSet<>();
  public long                               numRolloutDecisionNodeExpansions = 0;
  public double                             greedyRolloutEffectiveness       = 0;
  private int                               terminalCheckHorizon             = 500; //  Effectively infinite by default

  public int getNumTerminatingMoveProps()
  {
    return terminatingMoveProps.size();
  }

  public void clearTerminatingMoveProps()
  {
    terminatingMoveProps.clear();
  }

  public void setTerminalCheckHorizon(int horizon)
  {
    terminalCheckHorizon = horizon;
  }

  private ForwardDeadReckonProposition transitionToNextStateInGreedyRollout(TerminalResultSet results,
                                                                            Factor factor,
                                                                            ForwardDeadReckonProposition hintMoveProp,
                                                                            MoveWeights moveWeights,
                                                                            List<ForwardDeadReckonLegalMoveInfo> playedMoves)
      throws MoveDefinitionException, GoalDefinitionException
  {
    //		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.transitionToNextStateInGreedyRollout");
    //		try
    //		{
    ForwardDeadReckonLegalMoveSet activeLegalMoves = propNet
        .getActiveLegalProps(instanceId);
    int index = 0;
    boolean simultaneousMove = false;
    int maxChoices = 0;
    ForwardDeadReckonLegalMoveInfo choicelessMoveInfo = null;

    if (rolloutDecisionStack[rolloutStackDepth] == null)
    {
      rolloutDecisionStack[rolloutStackDepth] = new RolloutDecisionState();
      rolloutDecisionStack[rolloutStackDepth].nonChooserProps = new ForwardDeadReckonProposition[numRoles];
    }
    RolloutDecisionState decisionState = rolloutDecisionStack[rolloutStackDepth];
    if (decisionState.rolloutSeq != rolloutSeq)
    {
      decisionState.rolloutSeq = rolloutSeq;
      decisionState.chooserMoves = null;
    }

    if (decisionState.chooserMoves == null)
    {
      decisionState.choosingRole = null;
      decisionState.chooserIndex = -1;
      decisionState.baseChoiceIndex = -1;
      decisionState.nextChoiceIndex = -1;
      totalRoleoutNodesExamined++;

      for (Role role : getRoles())
      {
        Collection<ForwardDeadReckonLegalMoveInfo> moves;

        if ( factor == null )
        {
          moves = activeLegalMoves.getContents(role);
        }
        else
        {
          moves = new FactorFilteredForwardDeadReckonLegalMoveSet(factor, activeLegalMoves.getContents(role), false);
        }
        int numChoices = moves.size();

        if (numChoices > maxChoices)
        {
          maxChoices = numChoices;
        }

        if (numChoices > 1)
        {
          totalRoleoutChoices += numChoices;
          if (decisionState.choosingRole == null)
          {
            if (!simultaneousMove)
            {
              decisionState.choosingRole = role;
              decisionState.chooserMoves = new ForwardDeadReckonLegalMoveInfo[numChoices];
              decisionState.propProcessed = new boolean[numChoices];
            }
          }
          else
          {
            int rand = getRandom(decisionState.chooserMoves.length);

            ForwardDeadReckonLegalMoveInfo info = decisionState.chooserMoves[rand];
            chosenJointMoveProps[decisionState.chooserIndex] = info.inputProposition;
            if (playedMoves != null)
            {
              playedMoves.add(info);
            }

            decisionState.choosingRole = null;
            decisionState.chooserMoves = null;
            decisionState.propProcessed = null;
            simultaneousMove = true;
          }
        }

        if (simultaneousMove)
        {
          int rand = getRandom(numChoices);

          for (ForwardDeadReckonLegalMoveInfo info : moves)
          {
            if (rand-- <= 0)
            {
              decisionState.nonChooserProps[index++] = info.inputProposition;
              //chosenJointMoveProps[index++] = info.inputProposition;
              if (playedMoves != null)
              {
                playedMoves.add(info);
              }
              break;
            }
          }
        }
        else
        {
          int chooserMoveIndex = 0;
          for (ForwardDeadReckonLegalMoveInfo info : moves)
          {
            if (decisionState.choosingRole == role)
            {
              if (chooserMoveIndex == 0)
              {
                decisionState.chooserIndex = index++;
              }
              decisionState.chooserMoves[chooserMoveIndex++] = info;
            }
            else
            {
              decisionState.nonChooserProps[index++] = info.inputProposition;
              if (info.inputProposition != null || choicelessMoveInfo == null)
              {
                choicelessMoveInfo = info;
              }
              break;
            }
          }
        }
      }
    }

    if (simultaneousMove)
    {
      transitionToNextStateFromChosenMove(null, null);

      if (isTerminal())
      {
        results.considerResult(null);
      }
    }
    else if (decisionState.chooserIndex != -1)
    {
      //System.out.println("Specific chooser");
      int choiceIndex;
      boolean preEnumerate = false;
      int numTerminals = 0;

      if (decisionState.baseChoiceIndex == -1)
      {
        double total = 0;

        decisionState.state = new ForwardDeadReckonInternalMachineState(lastInternalSetState);

        for (ForwardDeadReckonLegalMoveInfo chooserMove : decisionState.chooserMoves)
        {
          if (moveWeights != null)
          {
            total += moveWeights.weightScore[chooserMove.globalMoveIndex];
          }
          if (!preEnumerate &&
              terminatingMoveProps.contains(chooserMove.inputProposition))
          {
            preEnumerate = true;
            numRolloutDecisionNodeExpansions++;
            if (moveWeights == null)
            {
              break;
            }
          }
        }

        if (moveWeights == null)
        {
          decisionState.baseChoiceIndex = getRandom(decisionState.chooserMoves.length);
        }
        else
        {
          total = getRandom((int)total);
        }

        for (int i = 0; i < decisionState.chooserMoves.length; i++)
        {
          decisionState.propProcessed[i] = false;
          if (decisionState.baseChoiceIndex == -1)
          {
            total -= moveWeights.weightScore[decisionState.chooserMoves[i].globalMoveIndex];
            if (total <= 0)
            {
              decisionState.baseChoiceIndex = i;
            }
          }
        }

        choiceIndex = decisionState.baseChoiceIndex;
      }
      else
      {
        choiceIndex = decisionState.nextChoiceIndex;
      }

      for (int roleIndex = 0; roleIndex < getRoles().size(); roleIndex++)
      {
        if (roleIndex != decisionState.chooserIndex)
        {
          chosenJointMoveProps[roleIndex] = decisionState.nonChooserProps[roleIndex];
        }
      }

      boolean transitioned = false;

      //	If we're given a hint move to check for a win do that first
      //	the first time we look at this node
      if (hintMoveProp != null && decisionState.chooserMoves.length > 1)
      {
        if (decisionState.baseChoiceIndex == choiceIndex)
        {
          for (int i = 0; i < decisionState.chooserMoves.length; i++)
          {
            if (decisionState.chooserMoves[i].inputProposition == hintMoveProp)
            {
              chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[i].inputProposition;

              transitionToNextStateFromChosenMove(null, null);

              if (isTerminal())
              {
                numTerminals++;

                //System.out.println("Encountered terminal state with goal value: "+ resultVector.scores.get(resultVector.controllingRole));
                if (getGoal(decisionState.choosingRole) == 100)
                {
                  if (playedMoves != null)
                  {
                    //System.out.println("Play hint move [" + playedMoves.size() + "]: " + decisionState.chooserMoves[i].move);
                    playedMoves.add(decisionState.chooserMoves[i]);
                  }
                  greedyRolloutEffectiveness++;
                  //	If we have a choosable win stop searching
                  return hintMoveProp;
                }

                results.considerResult(decisionState.choosingRole);

                decisionState.propProcessed[i] = true;
              }

              transitioned = true;
              break;
            }
          }
        }
        else
        {
          //	Not the first time we've looked at this node
          hintMoveProp = null;
        }
      }

      //	First time we visit the node try them all.  After that if we're asked to reconsider
      //	just take the next one from the last one we chose
      int remainingMoves = (decisionState.baseChoiceIndex == choiceIndex &&
                            preEnumerate ? decisionState.chooserMoves.length
                                        : 1);
      int choice = -1;
      int lastTransitionChoice = -1;

      for (int i = remainingMoves-1; i >= 0; i--)
      {
        choice = (i + choiceIndex) % decisionState.chooserMoves.length;

        //	Don't re-process the hint move that we looked at first unless this is the specific requested
        //  move
        if ( i > 0 )
        {
          if (decisionState.propProcessed[choice] ||
              hintMoveProp == decisionState.chooserMoves[choice].inputProposition ||
              (preEnumerate && !terminatingMoveProps.contains(decisionState.chooserMoves[choice].inputProposition)))
          {
            continue;
          }
        }

        if (transitioned)
        {
          setPropNetUsage(decisionState.state);
          setBasePropositionsFromState(decisionState.state, factor, true);
        }

        chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[choice].inputProposition;
        lastTransitionChoice = choice;

        transitionToNextStateFromChosenMove(null, null);

        transitioned = true;

        if (isTerminal())
        {
          numTerminals++;

          if ( rolloutStackDepth <= terminalCheckHorizon )
          {
            terminatingMoveProps
                .add(decisionState.chooserMoves[choice].inputProposition);
          }

          //System.out.println("Encountered terminal state with goal value: "+ resultVector.scores.get(resultVector.controllingRole));
          if (getGoal(decisionState.choosingRole) == 100)
          {
            if (playedMoves != null)
            {
              //System.out.println("Play winning move [" + playedMoves.size() + "]: " + decisionState.chooserMoves[choice].move);
              playedMoves.add(decisionState.chooserMoves[choice]);
            }
            if (preEnumerate)
            {
              greedyRolloutEffectiveness++;
            }

            //	If we have a choosable win stop searching
            return decisionState.chooserMoves[choice].inputProposition;
          }

          results.considerResult(decisionState.choosingRole);
          decisionState.propProcessed[choice] = true;
        }
      }

      if ( !transitioned )
      {
        chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[choice].inputProposition;
        lastTransitionChoice = choice;

        transitionToNextStateFromChosenMove(null, null);
      }

      if (playedMoves != null)
      {
        //System.out.println("Play non-winning move [" + playedMoves.size() + "]: " + decisionState.chooserMoves[lastTransitionChoice].move);
        playedMoves.add(decisionState.chooserMoves[lastTransitionChoice]);
      }

      decisionState.nextChoiceIndex = lastTransitionChoice;
      do
      {
        decisionState.nextChoiceIndex = (decisionState.nextChoiceIndex + 1) %
                                        decisionState.chooserMoves.length;
        if (!decisionState.propProcessed[decisionState.nextChoiceIndex] ||
            decisionState.nextChoiceIndex == decisionState.baseChoiceIndex)
        {
          break;
        }
      }
      while (decisionState.nextChoiceIndex != choiceIndex);

      if (preEnumerate && numTerminals > 0)
      {
        greedyRolloutEffectiveness += (decisionState.chooserMoves.length - numTerminals) /
                                      decisionState.chooserMoves.length;
      }
      //System.out.println("Transition move was: " + chosenJointMoveProps[0]);
      //System.out.println("State: " + mungedState(lastInternalSetState));
    }
    else
    {
      //System.out.println("No chooser");
      for (int roleIndex = 0; roleIndex < numRoles; roleIndex++)
      {
        chosenJointMoveProps[roleIndex] = decisionState.nonChooserProps[roleIndex];
        //System.out.println("Non chooser " + roleIndex + ": " + chosenJointMoveProps[roleIndex]);
      }
      transitionToNextStateFromChosenMove(null, null);
      //System.out.println("State: " + mungedState(lastInternalSetState));

      if (playedMoves != null)
      {
        playedMoves.add(choicelessMoveInfo);
      }
      if (isTerminal())
      {
        results.considerResult(decisionState.choosingRole);
      }
    }

    return null;
    //        }
    //		finally
    //		{
    //			methodSection.exitScope();
    //		}
  }

  private boolean hasAvailableMoveForAllRoles()
  {
    for (int roleIndex = 0; roleIndex < numRoles; roleIndex++)
    {
      Collection<ForwardDeadReckonLegalMoveInfo> moves = propNet.getActiveLegalProps(instanceId).getContents(roleIndex);

      if ( moves.isEmpty())
      {
        return false;
      }
    }

    return true;
  }

  private int chooseRandomJointMove(Factor factor,
                                    MoveWeights moveWeights,
                                    List<ForwardDeadReckonLegalMoveInfo> playedMoves)
  {
		ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.chooseRandomJointMove");
		try
		{
      int result = 0;
      int index = 0;
      ForwardDeadReckonLegalMoveSet activeLegalMoves = propNet.getActiveLegalProps(instanceId);

      for (int roleIndex = 0; roleIndex < numRoles; roleIndex++)
      {
        Collection<ForwardDeadReckonLegalMoveInfo> moves = activeLegalMoves.getContents(roleIndex);
        if ( factor != null )
        {
          //  In a factored game the terminal logic can sometimes span the factors in a way we don't
          //  cleanly cater for currently, so use lack of legal moves as a proxy for terminality
          if ( moves.isEmpty() )
          {
            return 0;
          }
          moves = new FactorFilteredForwardDeadReckonLegalMoveSet(factor, moves, false);
        }
        int numChoices = moves.size();
        int rand;

        if (moveWeights == null)
        {
          rand = getRandom(numChoices);
        }
        else
        {
          double total = 0;

          for (ForwardDeadReckonLegalMoveInfo info : moves)
          {
            if (moveWeights.weightScore[info.globalMoveIndex] == 0)
            {
              LOGGER.warn("Unexpected 0 move weight");
            }
            total += moveWeights.weightScore[info.globalMoveIndex];
          }

          if (total == 0)
          {
            LOGGER.warn("Unexpected 0 move weight total");
          }
          rand = getRandom((int)total);
        }

        if (numChoices > result)
        {
          result = numChoices;
        }

        ForwardDeadReckonLegalMoveInfo chosen = null;

        for (ForwardDeadReckonLegalMoveInfo info : moves)
        {
          if (moveWeights == null)
          {
            rand--;
          }
          else
          {
            rand -= moveWeights.weightScore[info.globalMoveIndex];
          }

          if (rand < 0)
          {
            chosen = info;
            break;
          }
        }

        assert(chosen != null);
        if (validationMachine != null)
        {
          chosenMoves[index] = chosen.move;
        }
        chosenJointMoveProps[index++] = chosen.inputProposition;
        if (playedMoves != null)
        {
          playedMoves.add(chosen);
        }
      }

      return result;
    }
		finally
		{
			methodSection.exitScope();
		}
  }

  private class TerminalResultVector
  {
    public Map<Role, Integer>             scores;
    public Role                           controllingRole;
    ForwardDeadReckonInternalMachineState state;

    public TerminalResultVector(Role controllingRole)
    {
      this.controllingRole = controllingRole;
      scores = new HashMap<>();
    }
  }

  private int     rolloutDepth;
  private boolean enableGreedyRollouts = true;

  public void disableGreedyRollouts()
  {
    if (!enableGreedyRollouts)
    {
      return;
    }

    enableGreedyRollouts = false;

    if (instanceId == 0)
    {
      //	Fixup the cross-reference of the base propositions of the two networks
      for (Entry<GdlSentence, PolymorphicProposition> e : propNetX.getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition oProp =
                               (ForwardDeadReckonProposition)propNetOWithoutGoals.getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition xProp =
                               (ForwardDeadReckonProposition)propNetXWithoutGoals.getBasePropositions().get(e.getKey());
        ForwardDeadReckonPropositionCrossReferenceInfo info =
                 (ForwardDeadReckonPropositionCrossReferenceInfo)((ForwardDeadReckonProposition)e.getValue()).getInfo();

        info.xNetProp = xProp;
        info.oNetProp = oProp;

        xProp.setInfo(info);
        oProp.setInfo(info);
      }

      propNetXWithoutGoals.crystalize(masterInfoSet, maxInstances);
      propNetOWithoutGoals.crystalize(masterInfoSet, maxInstances);

      for(ForwardDeadReckonPropositionInfo info : masterInfoSet)
      {
        ForwardDeadReckonPropositionCrossReferenceInfo crInfo = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

        crInfo.xNetPropId = crInfo.xNetProp.id;
        crInfo.oNetPropId = crInfo.oNetProp.id;
      }

      propNetXWithoutGoals.reset(true);
      propNetOWithoutGoals.reset(true);

      List<ForwardDeadReckonLegalMoveInfo> allMoves = new ArrayList<>();
      for (ForwardDeadReckonLegalMoveInfo info : propNetXWithoutGoals.getMasterMoveList())
      {
        if (!allMoves.contains(info))
        {
          info.globalMoveIndex = allMoves.size();
          allMoves.add(info);
        }
      }
      for (ForwardDeadReckonLegalMoveInfo info : propNetOWithoutGoals.getMasterMoveList())
      {
        if (!allMoves.contains(info))
        {
          info.globalMoveIndex = allMoves.size();
          allMoves.add(info);
        }
      }

      setupAllMovesArrayFromList(allMoves);
    }

    propNetO = propNetOWithoutGoals;
    propNetX = propNetXWithoutGoals;

    propNet = propNetX;
    lastInternalSetState = null;
    lastInternalSetStateX = null;
    lastInternalSetStateO = null;
  }

  private void setupAllMovesArrayFromList(List<ForwardDeadReckonLegalMoveInfo> allMoves)
  {
    allMovesInfo = allMoves.toArray(allMovesInfo);

    //  Annotate the moves with their associated factors
    if ( factors != null )
    {
      //  Moves with no dependencies (typically a noop) can appear in multiple factors, but
      //  should be tagged as factor-free
      Set<ForwardDeadReckonLegalMoveInfo> multiFactorMoves = new HashSet<>();

      for(ForwardDeadReckonLegalMoveInfo info : allMovesInfo)
      {
        for(Factor factor : factors)
        {
          if ( factor.getMoves().contains(info.move))
          {
            if ( info.factor != null )
            {
              multiFactorMoves.add(info);
            }
            info.factor = factor;
          }
        }
      }

      for(ForwardDeadReckonLegalMoveInfo info : multiFactorMoves)
      {
        info.factor = null;
      }
    }
  }

  private ForwardDeadReckonLegalMoveInfo[] allMovesInfo = new ForwardDeadReckonLegalMoveInfo[1];

  public MoveWeights createMoveWeights()
  {
    return new MoveWeights(allMovesInfo.length, getRoles().size());
  }

  public ForwardDeadReckonInternalMachineState getCurrentState()
  {
    return lastInternalSetState;
  }

  public void getDepthChargeResult(ForwardDeadReckonInternalMachineState state,
                                  Factor factor,
                                  Role role,
                                  final int[] stats,
                                  MoveWeights moveWeights,
                                  List<ForwardDeadReckonLegalMoveInfo> playedMoves)
      throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getDepthChargeResult");
    try
    {
      rolloutDepth = 0;
      TerminalResultSet resultSet = ((enableGreedyRollouts && numRoles <= 2) ? new TerminalResultSet(getRoles())
                                                                                     : null);
      if (validationMachine != null)
      {
        validationState = state.getMachineState();
      }
      setPropNetUsage(state);
      setBasePropositionsFromState(state, factor, true);
      for (int i = 0; i < numRoles; i++)
      {
        if (ForwardDeadReckonPropNet.useFastAnimator)
        {
          previouslyChosenJointMovePropIdsX[i] = -1;
          previouslyChosenJointMovePropIdsO[i] = -1;
        }
        else
        {
          previouslyChosenJointMovePropsX[i] = null;
          previouslyChosenJointMovePropsO[i] = null;
        }
      }
      if (resultSet == null)
      {
        int totalChoices = 0;

        while (!isTerminal())
        {
          int numChoices = chooseRandomJointMove(factor, moveWeights, playedMoves);
          totalChoices += numChoices;
          transitionToNextStateFromChosenMove(null, null);
          rolloutDepth++;
        }

        if (stats != null)
        {
          stats[0] = rolloutDepth;
          stats[1] = (totalChoices + rolloutDepth / 2) / rolloutDepth;
        }
      }
      else
      {
        double branchingFactor = recursiveGreedyRollout(resultSet,
                                                        factor,
                                                        moveWeights,
                                                        playedMoves);

        if (stats != null)
        {
          stats[0] = rolloutStackDepth;
          stats[1] = (int)(branchingFactor + 0.5);
        }

        if (resultSet.resultVector.controllingRole != null)
        {
          setPropNetUsage(resultSet.resultVector.state);
          setBasePropositionsFromState(resultSet.resultVector.state, null, true);
        }
      }
      for (int i = 0; i < numRoles; i++)
      {
        if (ForwardDeadReckonPropNet.useFastAnimator)
        {
          int xId = previouslyChosenJointMovePropIdsX[i];
          int oId = previouslyChosenJointMovePropIdsO[i];

          if ( xId != -1)
          {
            propNetX.animator.setComponentValue(instanceId, xId, false);
          }
          if ( oId != -1)
          {
            propNetO.animator.setComponentValue(instanceId, oId, false);
          }
        }
        else
        {
          if (previouslyChosenJointMovePropsX[i] != null)
          {
             previouslyChosenJointMovePropsX[i].setValue(false, instanceId);
          }
          if (previouslyChosenJointMovePropsO[i] != null)
          {
            previouslyChosenJointMovePropsO[i].setValue(false, instanceId);
          }
        }
      }
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public Set<Factor> getFactors()
  {
    return factors;
  }

  public void disableFactorization()
  {
    factors = null;
  }

  public Set<GdlSentence> getBasePropositions()
  {
    return fullPropNet.getBasePropositions().keySet();
  }

  public GdlSentence getXSentence()
  {
    return XSentence;
  }

  public GdlSentence getOSentence()
  {
    return OSentence;
  }

  private ForwardDeadReckonInternalMachineState lastGoalState = null;
  private ForwardDeadReckonInternalMachineState nextGoalState = null;

  public int getGoal(ForwardDeadReckonInternalMachineState state, Role role)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getGoal");
    try
    {
      ForwardDeadReckonPropNet net;

      if (enableGreedyRollouts)
      {
        if (state != null)
        {
          setPropNetUsage(state);
          setBasePropositionsFromState(state, null, true);
        }

        net = propNet;
      }
      else
      {
        net = goalsNet;

        if (state == null)
        {
          state = lastInternalSetState;
        }

        if (lastGoalState == null)
        {
          for (PolymorphicProposition p : net.getBasePropositionsArray())
          {
            net.setProposition(instanceId, (ForwardDeadReckonProposition)p, false);
            //((ForwardDeadReckonProposition)p).setValue(false, instanceId);
          }
          for (ForwardDeadReckonPropositionInfo s : state)
          {
            ForwardDeadReckonPropositionCrossReferenceInfo scr = (ForwardDeadReckonPropositionCrossReferenceInfo)s;
            if (scr.goalsNetProp != null)
            {
              net.setProposition(instanceId, scr.goalsNetProp, true);
              //scr.goalsNetProp.setValue(true, instanceId);
            }
          }

          if (lastGoalState == null)
          {
            lastGoalState = new ForwardDeadReckonInternalMachineState(state);
          }
          else
          {
            lastGoalState.copy(state);
          }
        }
        else if (!state.equals(lastGoalState))
        {
          if (nextGoalState == null)
          {
            nextGoalState = new ForwardDeadReckonInternalMachineState(state);
          }
          else
          {
            nextGoalState.copy(state);
          }

          lastGoalState.xor(state);

          for (ForwardDeadReckonPropositionInfo info : lastGoalState)
          {
            ForwardDeadReckonProposition goalsNetProp = ((ForwardDeadReckonPropositionCrossReferenceInfo)info).goalsNetProp;
            if (goalsNetProp != null)
            {
              if (nextGoalState.contains(info))
              {
                net.setProposition(instanceId, goalsNetProp, true);
                //goalsNetProp.setValue(true, instanceId);
              }
              else
              {
                net.setProposition(instanceId, goalsNetProp, false);
                //goalsNetProp.setValue(false, instanceId);
              }
            }
          }

          lastGoalState.copy(nextGoalState);
        }
      }

      //  HACK - for factored games we might be determining terminality
      //  based on factor termination through lack of legal moves, but not
      //  actually have a complete game state that registers as terminal.
      //  In such cases the goal network may also rely on global terminality
      //  and we attempt to best-guess the actual goal in this case.  Specifically
      //  if the game is terminal in a factor but not globally terminal, and all
      //  roles report the same score, we ASSUME it should be a normalized draw
      //  with all scoring 50.
      if ( factors != null && !isTerminalUnfactored() && isTerminal() )
      {
        int observedResult = -1;
        boolean goalDifferentiated = false;
        boolean roleSeen = false;

        for(Role r : getRoles())
        {
          int value = extractRoleGoal(net, role);

          if ( observedResult == -1 )
          {
            observedResult = value;
          }
          else if ( observedResult != value )
          {
            if ( roleSeen )
            {
              break;
            }

            observedResult = value;
            goalDifferentiated = true;
          }

          if ( role.equals(r) )
          {
            roleSeen = true;
            if ( goalDifferentiated )
            {
              break;
            }
          }
        }

        if ( !goalDifferentiated )
        {
          observedResult = 50;
        }

        return observedResult;
      }

      return extractRoleGoal(net, role);
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private int extractRoleGoal(ForwardDeadReckonPropNet net, Role role)
  {
    PolymorphicProposition[] goalProps = net.getGoalPropositions().get(role);
    int result = 0;

    for (PolymorphicProposition p : goalProps)
    {
      //ForwardDeadReckonComponent goalInput = (ForwardDeadReckonComponent)p.getSingleInput();
      //if (goalInput != null && goalInput.getValue(instanceId))
      //if (goalInput != null && net.getTransition(instanceId, goalInput))
      if (net.getComponentValue(instanceId, (ForwardDeadReckonComponent)p))
      {
        result = Integer.parseInt(p.getName().getBody().get(1).toString());
        break;
      }
    }

    return result;
  }
}
