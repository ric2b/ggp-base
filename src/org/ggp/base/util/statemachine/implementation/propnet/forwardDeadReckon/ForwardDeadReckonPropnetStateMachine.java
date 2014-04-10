
package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
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
  /** The underlying proposition network */
  private ForwardDeadReckonPropNet                                     propNetX                        = null;
  private ForwardDeadReckonPropNet                                     propNetO                        = null;
  private ForwardDeadReckonPropNet                                     propNetXWithoutGoals            = null;
  private ForwardDeadReckonPropNet                                     propNetOWithoutGoals            = null;
  private ForwardDeadReckonPropNet                                     propNet                         = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositionsX              = null;
  private Map<Role, Move[]>                                            legalPropositionMovesX          = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositionsO              = null;
  private Map<Role, Move[]>                                            legalPropositionMovesO          = null;
  private Map<Role, ForwardDeadReckonComponent[]>                      legalPropositions               = null;
  /** The player roles */
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
  private Map<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> basePropChangeCounts            = new HashMap<ForwardDeadReckonPropositionCrossReferenceInfo, Integer>();
  private ForwardDeadReckonProposition[]                               chosenJointMoveProps            = null;
  private Move[]                                                       chosenMoves                     = null;
  private ForwardDeadReckonProposition[]                               previouslyChosenJointMovePropsX = null;
  private ForwardDeadReckonProposition[]                               previouslyChosenJointMovePropsO = null;
  private ForwardDeadReckonPropositionCrossReferenceInfo[]             masterInfoSet                   = null;
  private StateMachine                                                 validationMachine               = null;
  private MachineState                                                 validationState                 = null;
  private ForwardDeadReckonPropNet                                     fullPropNet                     = null;
  private ForwardDeadReckonPropNet                                     goalsNet                        = null;
  private int                                                          instanceId;
  private int                                                          maxInstances;
  private int                                                          numInstances                    = 1;
  private Set<Factor>                                                  factors                         = null;
  public long                                                          totalNumGatesPropagated         = 0;
  public long                                                          totalNumPropagates              = 0;

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
        ForwardDeadReckonProposition p = (ForwardDeadReckonProposition)propNet
            .getBasePropositions().get(s);
        ForwardDeadReckonPropositionInfo info = p
            .getInfo();
        result.add(info);

        result.isXState |= (info.sentence == XSentence);
      }

      //System.out.println("Created internal state: " + result + " with hash " + result.hashCode());
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

  public MachineState findTerminalState()
  {
    HashSet<GdlSentence> terminalSentences = new HashSet<GdlSentence>();

    for (GdlSentence s : propNet.getBasePropositions().keySet())
    {
      String sentenceString = s.toString();
      if (sentenceString.contains("cell 1 1 1") ||
          sentenceString.contains("cell 1 2 2") ||
          sentenceString.contains("cell 1 3 3") ||
          sentenceString.contains("cell 2 1 4") ||
          sentenceString.contains("cell 2 2 5") ||
          sentenceString.contains("cell 2 3 6") ||
          sentenceString.contains("cell 3 1 7") ||
          sentenceString.contains("cell 3 2 8") ||
          sentenceString.contains("cell 3 3 b"))
      {
        terminalSentences.add(s);
      }
    }

    return new MachineState(terminalSentences);
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
    Set<MachineState> results = new HashSet<MachineState>();

    for (PolymorphicProposition p : fullPropNet.getGoalPropositions()
        .get(role))
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
      positiveProps = new HashSet<PolymorphicProposition>();
      negativeProps = new HashSet<PolymorphicProposition>();
      isPositive = true;
    }

    public AntecedantCursor(AntecedantCursor parent)
    {
      positiveProps = new HashSet<PolymorphicProposition>(parent.positiveProps);
      negativeProps = new HashSet<PolymorphicProposition>(parent.negativeProps);
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
      System.out.println("Last set state mismtch");
    }

    for (PolymorphicProposition p : propNet.getBasePropositionsArray())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;

      if (fdrp.getValue(instanceId) != fdrp.getValue(other.instanceId))
      {
        System.out.println("Base prop state mismatch on: " + p);
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
            Set<AntecedantCursor> result = new HashSet<AntecedantCursor>();
            result.add(newCursor);
            return result;
          }
          else if (!cursor.positiveProps.contains(p))
          {
            newCursor.negativeProps.add(prop);
            Set<AntecedantCursor> result = new HashSet<AntecedantCursor>();
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
          Set<AntecedantCursor> result = new HashSet<AntecedantCursor>();
          result.add(newCursor);
          return result;
        }
        else if (!cursor.negativeProps.contains(p))
        {
          newCursor.positiveProps.add(prop);
          Set<AntecedantCursor> result = new HashSet<AntecedantCursor>();
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
      Set<AntecedantCursor> subResults = new HashSet<AntecedantCursor>();

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
              Set<AntecedantCursor> validInputResults = new HashSet<AntecedantCursor>();

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
      Set<AntecedantCursor> subResults = new HashSet<AntecedantCursor>();

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
              Set<AntecedantCursor> validInputResults = new HashSet<AntecedantCursor>();

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
    Set<MachineState> result = new HashSet<MachineState>();

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

  public ForwardDeadReckonPropnetStateMachine(int maxInstances,
                                                  GdlConstant roleName)
  {
    this.maxInstances = maxInstances;
  }

  private ForwardDeadReckonPropnetStateMachine(ForwardDeadReckonPropnetStateMachine master,
                                                   int instanceId)
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
    this.fullPropNet = master.fullPropNet;
    this.masterInfoSet = master.masterInfoSet;
    this.factors = master.factors;

    stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
    stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

    moveProps = new ForwardDeadReckonProposition[roles.size()];
    chosenJointMoveProps = new ForwardDeadReckonProposition[roles.size()];
    chosenMoves = new Move[roles.size()];
    previouslyChosenJointMovePropsX = new ForwardDeadReckonProposition[roles
        .size()];
    previouslyChosenJointMovePropsO = new ForwardDeadReckonProposition[roles
        .size()];

    stats = new TestPropnetStateMachineStats(fullPropNet.getBasePropositions()
        .size(), fullPropNet.getInputPropositions().size(), fullPropNet
        .getLegalPropositions().get(getRoles().get(0)).length);
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
    setRandomSeed(1);

    try
    {
      //validationMachine = new ProverStateMachine();
      //validationMachine.initialize(description);

      fullPropNet = (ForwardDeadReckonPropNet)OptimizingPolymorphicPropNetFactory
          .create(description, new ForwardDeadReckonComponentFactory());
      fullPropNet.renderToFile("c:\\temp\\propnet.dot");

      OptimizingPolymorphicPropNetFactory
          .removeAnonymousPropositions(fullPropNet);
      System.out.println("Num components after anon prop removal: " +
                         fullPropNet.getComponents().size());
      OptimizingPolymorphicPropNetFactory
          .removeUnreachableBasesAndInputs(fullPropNet);
      System.out.println("Num components after unreachable removal: " +
                         fullPropNet.getComponents().size());
      OptimizingPolymorphicPropNetFactory
          .removeRedundantConstantsAndGates(fullPropNet, false);
      System.out
          .println("Num components after first pass redundant components removal: " +
                   fullPropNet.getComponents().size());
      OptimizingPolymorphicPropNetFactory.refactorLargeGates(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnetBeforeLargeFanout.dot");
      OptimizingPolymorphicPropNetFactory.refactorLargeFanouts(fullPropNet);
      fullPropNet.renderToFile("c:\\temp\\propnetAfterLargeFanout.dot");
      System.out.println("Num components after large gate refactoring: " +
                         fullPropNet.getComponents().size());
      OptimizingPolymorphicPropNetFactory.removeDuplicateLogic(fullPropNet);
      System.out.println("Num components after duplicate removal: " +
                         fullPropNet.getComponents().size());
      //OptimizingPolymorphicPropNetFactory.optimizeInputSets(fullPropNet);
      //System.out.println("Num components after input set optimization: " + fullPropNet.getComponents().size());
      OptimizingPolymorphicPropNetFactory.OptimizeInvertedInputs(fullPropNet);
      System.out.println("Num components after inverted input optimization: " +
                         fullPropNet.getComponents().size());
      OptimizingPolymorphicPropNetFactory
          .removeRedundantConstantsAndGates(fullPropNet);
      System.out
          .println("Num components after further removal of redundant components: " +
                   fullPropNet.getComponents().size());

      fullPropNet.renderToFile("c:\\temp\\propnetReduced.dot");
      roles = fullPropNet.getRoles();

      moveProps = new ForwardDeadReckonProposition[roles.size()];
      chosenJointMoveProps = new ForwardDeadReckonProposition[roles.size()];
      chosenMoves = new Move[roles.size()];
      previouslyChosenJointMovePropsX = new ForwardDeadReckonProposition[roles
          .size()];
      previouslyChosenJointMovePropsO = new ForwardDeadReckonProposition[roles
          .size()];
      stats = new TestPropnetStateMachineStats(fullPropNet
                                                   .getBasePropositions()
                                                   .size(),
                                               fullPropNet
                                                   .getInputPropositions()
                                                   .size(),
                                               fullPropNet
                                                   .getLegalPropositions()
                                                   .get(getRoles().get(0)).length);
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
      System.out.println("Num components: " + numComponents +
                         " with an average of " + numInputs / numComponents +
                         " inputs.");
      System.out
          .println("Num multi-input components: " +
                   numMultiInputComponents +
                   " with an average of " +
                   (numMultiInputComponents == 0 ? "N/A" : numMultiInputs /
                                                           numMultiInputComponents) +
                   " inputs.");

      masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet
          .getBasePropositions().size()];
      int index = 0;
      for (Entry<GdlSentence, PolymorphicProposition> e : fullPropNet
          .getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition prop = (ForwardDeadReckonProposition)e
            .getValue();
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

      FactorAnalyser factorAnalyser = new FactorAnalyser(this);
      factors = factorAnalyser.analyse();

      if ( factors != null )
      {
        System.out.println("Game appears factorize into " + factors.size() + " factors");
      }

      fullPropNet.crystalize(masterInfoSet, maxInstances);

      stateBufferX1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferX2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO1 = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      stateBufferO2 = new ForwardDeadReckonInternalMachineState(masterInfoSet);

      fullPropNet.reset(false);
      fullPropNet.getInitProposition().setValue(true);
      fullPropNet.propagate(0);
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
      for (Entry<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> e : basePropChangeCounts
          .entrySet())
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

      propNetX = new ForwardDeadReckonPropNet(fullPropNet,
                                              new ForwardDeadReckonComponentFactory());
      propNetO = new ForwardDeadReckonPropNet(fullPropNet,
                                              new ForwardDeadReckonComponentFactory());
      goalsNet = new ForwardDeadReckonPropNet(fullPropNet,
                                              new ForwardDeadReckonComponentFactory());
      propNetX.RemoveInits();
      propNetO.RemoveInits();

      if (XSentence != null)
      {
        System.out.println("Reducing with respect to XSentence: " + XSentence);
        OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX,
                                                               XSentence,
                                                               true);

        //	If the reduced net always transitions it's own hard-wired sentence into the opposite state
        //	it may be part of a pivot whereby control passes between alternating propositions.  Check this
        //	Do we turn something else on unconditionally?
        for (Entry<GdlSentence, PolymorphicProposition> e : propNetX
            .getBasePropositions().entrySet())
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
          System.out.println("Possible OSentence: " + OSentence);
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO,
                                                                 OSentence,
                                                                 true);

          //	Does this one turn the original back on?
          PolymorphicProposition originalPropInSecondNet = propNetO
              .getBasePropositions().get(XSentence);
          if (originalPropInSecondNet != null)
          {
            PolymorphicComponent input = originalPropInSecondNet
                .getSingleInput();

            if (input instanceof PolymorphicTransition)
            {
              PolymorphicComponent driver = input.getSingleInput();

              if (!(driver instanceof PolymorphicConstant) ||
                  !driver.getValue())
              {
                //	Nope - doesn't work
                OSentence = null;
                System.out.println("Fails to recover back-transition to " +
                                   XSentence);
              }
            }
          }

          if (OSentence != null)
          {
            //	So if we set the first net's trigger condition to off in the second net do we find
            //	the second net's own trigger is always off?
            OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO,
                                                                   XSentence,
                                                                   false);

            PolymorphicProposition OSentenceInSecondNet = propNetO
                .getBasePropositions().get(OSentence);
            if (OSentenceInSecondNet != null)
            {
              PolymorphicComponent input = OSentenceInSecondNet
                  .getSingleInput();

              if (input instanceof PolymorphicTransition)
              {
                PolymorphicComponent driver = input.getSingleInput();

                if (!(driver instanceof PolymorphicConstant) ||
                    driver.getValue())
                {
                  //	Nope - doesn't work
                  System.out
                      .println("Fails to recover back-transition remove of " +
                               OSentence);
                  OSentence = null;
                }

                //	Finally, if we set the OSentence off in the first net do we recover the fact that
                //	the XSentence always moves to off in transitions from the first net?
                if (OSentence != null)
                {
                  OptimizingPolymorphicPropNetFactory
                      .fixBaseProposition(propNetX, OSentence, false);

                  PolymorphicProposition XSentenceInFirstNet = propNetX
                      .getBasePropositions().get(XSentence);
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
                        System.out.println("Fails to recover removal of " +
                                           XSentence);
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
          System.out.println("Reverting OSentence optimizations");
          //	Failed - best we can do is simply drive the XSentence to true in one network
          propNetX = new ForwardDeadReckonPropNet(fullPropNet,
                                                  new ForwardDeadReckonComponentFactory());
          propNetO = new ForwardDeadReckonPropNet(fullPropNet,
                                                  new ForwardDeadReckonComponentFactory());
          propNetX.RemoveInits();
          propNetO.RemoveInits();
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX,
                                                                 XSentence,
                                                                 true);
          OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO,
                                                                 XSentence,
                                                                 false);
        }
        //OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
        propNetX.renderToFile("c:\\temp\\propnetReducedX.dot");
        propNetO.renderToFile("c:\\temp\\propnetReducedO.dot");
        System.out.println("Num components remaining in X-net: " +
                           propNetX.getComponents().size());
        System.out.println("Num components remaining in O-net: " +
                           propNetO.getComponents().size());
      }

      propNetXWithoutGoals = new ForwardDeadReckonPropNet(propNetX,
                                                          new ForwardDeadReckonComponentFactory());
      propNetOWithoutGoals = new ForwardDeadReckonPropNet(propNetO,
                                                          new ForwardDeadReckonComponentFactory());
      propNetXWithoutGoals.RemoveGoals();
      propNetOWithoutGoals.RemoveGoals();
      OptimizingPolymorphicPropNetFactory
          .minimizeNetwork(propNetXWithoutGoals);
      OptimizingPolymorphicPropNetFactory
          .minimizeNetwork(propNetOWithoutGoals);
      propNetXWithoutGoals.renderToFile("c:\\temp\\propnetXWithoutGoals.dot");
      propNetOWithoutGoals.renderToFile("c:\\temp\\propnetROWithoutGoals.dot");
      System.out.println("Num components remaining in goal-less X-net: " +
                         propNetXWithoutGoals.getComponents().size());
      System.out.println("Num components remaining in goal-less O-net: " +
                         propNetOWithoutGoals.getComponents().size());

      goalsNet.RemoveAllButGoals();
      System.out.println("Goal net left with " +
                         goalsNet.getComponents().size() + " components");
      goalsNet.renderToFile("c:\\temp\\propnetGoalsReduced.dot");

      //masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet
      //    .getBasePropositions().size()];
      //index = 0;
      //	Cross-reference the base propositions of the two networks
      for (Entry<GdlSentence, PolymorphicProposition> e : fullPropNet
          .getBasePropositions().entrySet())
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
      goalsNet.reset(true);
      //	Force calculation of the goal set while we're single threaded
      goalsNet.getGoalPropositions();

      List<ForwardDeadReckonLegalMoveInfo> allMoves = new ArrayList<ForwardDeadReckonLegalMoveInfo>();
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
    //ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.setBasePropositionsInternal");
    //try
    {
      //System.out.println("Set state for instance " + instanceId + ": " + state);
      //System.out.println("Last set state for instance " + instanceId + " was: " + lastInternalSetState);
      if (lastInternalSetState != null)
      {
        if (!lastInternalSetState.equals(state))
        {
          ForwardDeadReckonInternalMachineState nextInternalSetState;

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

          for (ForwardDeadReckonPropositionInfo info : lastInternalSetState)
          {
            ForwardDeadReckonPropositionCrossReferenceInfo infoCr = (ForwardDeadReckonPropositionCrossReferenceInfo)info;

            //if ( factor == null || factor.getStateMask().contains(infoCr))
            {
              if (nextInternalSetState.contains(info))
              {
                if (propNet == propNetX)
                {
                  infoCr.xNetProp.setValue(true, instanceId);
                }
                else
                {
                  infoCr.oNetProp.setValue(true, instanceId);
                }
              }
              else
              {
                if (propNet == propNetX)
                {
                  infoCr.xNetProp.setValue(false, instanceId);
                }
                else
                {
                  infoCr.oNetProp.setValue(false, instanceId);
                }
              }
            }

            if (measuringBasePropChanges)
            {
              basePropChangeCounts.put(infoCr,
                                       basePropChangeCounts.get(infoCr) + 1);
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
        //System.out.println("Setting entire state");
        for (PolymorphicProposition p : propNet.getBasePropositionsArray())
        {
          ((ForwardDeadReckonProposition)p).setValue(false, instanceId);
        }
        for (ForwardDeadReckonPropositionInfo s : state)
        {
          ForwardDeadReckonPropositionCrossReferenceInfo sCr = (ForwardDeadReckonPropositionCrossReferenceInfo)s;
          if (propNet == propNetX)
          {
            sCr.xNetProp.setValue(true, instanceId);
          }
          else
          {
            sCr.oNetProp.setValue(true, instanceId);
          }
        }
      }
    }
    //finally
    //{
    //	methodSection.exitScope();
    //}
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
      boolean result = ((ForwardDeadReckonComponent)terminalProp
          .getSingleInput()).getValue(instanceId);
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
    //ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.isTerminal");
    //try
    {
      setPropNetUsage(state);
      setBasePropositionsFromState(state, null, true);

      return isTerminal();
    }
    //finally
    //{
    //	methodSection.exitScope();
    //}
  }

  public boolean isTerminal()
  {
    //ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.isTerminal");
    //try
    {
      if ( factors != null && !hasAvailableMoveForAllRoles() )
      {
        return true;
      }

      return isTerminalUnfactored();
    }
    //finally
    //{
    //	methodSection.exitScope();
    //}
  }

  private boolean isTerminalUnfactored()
  {
    PolymorphicProposition terminalProp = propNet.getTerminalProposition();
    boolean result = ((ForwardDeadReckonComponent)terminalProp
        .getSingleInput()).getValue(instanceId);
    //if ( result )
    //{
    //  System.out.println("State " + state + " is terminal");
    //}

    if (validationMachine != null)
    {
      if (validationMachine.isTerminal(validationState) != result)
      {
        System.out.println("Terminality mismatch");
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
      throws GoalDefinitionException
  {
    ForwardDeadReckonInternalMachineState internalState = createInternalState(state);

    return getGoal(internalState, role);
  }

  public int getGoal(Role role) throws GoalDefinitionException
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
    //System.out.println("Initial state: " + result);
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

      result = new LinkedList<Move>();
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

      result = new LinkedList<Move>();
      for (ForwardDeadReckonLegalMoveInfo moveInfo : propNet
          .getActiveLegalProps(instanceId).getContents(role))
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

  public Collection<ForwardDeadReckonLegalMoveInfo> getLegalMoves(ForwardDeadReckonInternalMachineState state, Role role, Factor factor)
      throws MoveDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getLegalMoveInfos");
    try
    {
      setPropNetUsage(state);
      setBasePropositionsFromState(state, factor, true);

      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;
      Collection<ForwardDeadReckonLegalMoveInfo> baseIterable = propNet.getActiveLegalProps(instanceId).getContents(role);
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

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet
          .getInputPropositions();
      int movesCount = 0;

      for (GdlSentence moveSentence : toDoes(moves))
      {
        ForwardDeadReckonProposition moveInputProposition = (ForwardDeadReckonProposition)inputProps
            .get(moveSentence);
        if (moveInputProposition != null)
        {
          moveInputProposition.setValue(true, instanceId);
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
        moveProps[i].setValue(false, instanceId);
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
          moveInputProposition.setValue(true, instanceId);
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
          moveProps[i].setValue(false, instanceId);
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

      Map<GdlSentence, PolymorphicProposition> inputProps = propNet
          .getInputPropositions();
      int movesCount = 0;

      for (GdlSentence moveSentence : toDoes(moves))
      {
        ForwardDeadReckonProposition moveInputProposition = (ForwardDeadReckonProposition)inputProps
            .get(moveSentence);
        if (moveInputProposition != null)
        {
          moveInputProposition.setValue(true, instanceId);
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
        moveProps[i].setValue(false, instanceId);
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
      //			boolean stateError = false;
      //			do
      //			{
      //				if ( state != null && !state.toString().contains("step"))
      //				{
      //					System.out.println("Illegal state passed to getNextState()");
      //				}
      setPropNetUsage(state);
      //for(PolymorphicComponent c : propNet.getComponents())
      //{
      //	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
      //}
      //ForwardDeadReckonComponent.numGatesPropagated = 0;
      //ForwardDeadReckonComponent.numPropagates = 0;
      //propNet.seq++;

      int movesCount = 0;

      for (ForwardDeadReckonLegalMoveInfo move : moves)
      {
        if ( !move.isPseudoNoOp)
        {
          ForwardDeadReckonProposition moveInputProposition = move.inputProposition;
          if (moveInputProposition != null)
          {
            moveInputProposition.setValue(true, instanceId);
            moveProps[movesCount++] = moveInputProposition;
          }
        }
      }

      setBasePropositionsFromState(state, factor, true);

      result = getInternalStateFromBase();

      //System.out.println("After move " + moves + " in state " + state + " resulting state is " + result);
      //totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
      //totalNumPropagates += ForwardDeadReckonComponent.numPropagates;

      for (int i = 0; i < movesCount; i++)
      {
        moveProps[i].setValue(false, instanceId);
      }

      if ( movesCount == 0 && factor != null )
      {
        //  Hack - re-impose the base props from the starting state.  We need to do it this
        //  way in order for the non-factor turn logic (control prop, step, etc) to generate
        //  correctly, but then make sure we haver not changed any factor-specific base props
        //  which can happen because no moves were played (consider distinct clauses on moves)
        ForwardDeadReckonInternalMachineState basePropState = new ForwardDeadReckonInternalMachineState(state);

        basePropState.intersect(factor.getStateMask(true));
        result.intersect(factor.getInverseStateMask(true));
        result.merge(basePropState);
      }
      //				if ( !result.toString().contains("step"))
      //				{
      //					System.out.println("Illegal state generated by getNextState()");
      //					stateError = true;
      //				}
      //			} while(stateError);
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
        List<Move> moves = new LinkedList<Move>();

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

        if (propNet == propNetX)
        {
          previousChosenMove = previouslyChosenJointMovePropsX[index];
        }
        else
        {
          previousChosenMove = previouslyChosenJointMovePropsO[index];
        }
        if (moveProp != null)
        {
          moveProp.setValue(true, instanceId);
          //System.out.println("Move: " + moveProp.getName());
        }
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
    List<GdlSentence> doeses = new ArrayList<GdlSentence>(moves.length);
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (int i = 0; i < roles.size(); i++)
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
    List<GdlSentence> doeses = new ArrayList<GdlSentence>(moves.size());
    Map<Role, Integer> roleIndices = getRoleIndices();

    for (int i = 0; i < roles.size(); i++)
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

      setBasePropositionsFromState(transitionTo, null, true);
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

  private ForwardDeadReckonInternalMachineState getInternalStateFromBase()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TestPropnetStateMachine.getStateFromBase");
    try
    {
      //RuntimeOptimizedComponent.getCount = 0;

      ForwardDeadReckonInternalMachineState result = new ForwardDeadReckonInternalMachineState(masterInfoSet);
      for (ForwardDeadReckonPropositionInfo info : propNet
          .getActiveBaseProps(instanceId))
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

  private Map<Role, List<Move>> recentLegalMoveSetsList = new HashMap<Role, List<Move>>();

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
        candidates = new LinkedList<Move>();

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
        throws GoalDefinitionException
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

  private Set<ForwardDeadReckonProposition> terminatingMoveProps             = new HashSet<ForwardDeadReckonProposition>();
  public long                               numRolloutDecisionNodeExpansions = 0;
  public double                             greedyRolloutEffectiveness       = 0;

  public int getNumTerminatingMoveProps()
  {
    return terminatingMoveProps.size();
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

    if (rolloutDecisionStack[rolloutStackDepth] == null)
    {
      rolloutDecisionStack[rolloutStackDepth] = new RolloutDecisionState();
      rolloutDecisionStack[rolloutStackDepth].nonChooserProps = new ForwardDeadReckonProposition[getRoles()
          .size()];
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

      for (int i = 0; i < remainingMoves; i++)
      {
        choice = (i + choiceIndex) % decisionState.chooserMoves.length;

        //	Don't reprocess the hint move that we looked at first
        if (decisionState.propProcessed[choice] ||
            hintMoveProp == decisionState.chooserMoves[choice].inputProposition)
        {
          continue;
        }

        if (transitioned)
        {
          setPropNetUsage(decisionState.state);
          setBasePropositionsFromState(decisionState.state, factor, true);
        }

        chosenJointMoveProps[decisionState.chooserIndex] = decisionState.chooserMoves[choice].inputProposition;

        transitionToNextStateFromChosenMove(null, null);

        transitioned = true;

        if (isTerminal())
        {
          numTerminals++;

          terminatingMoveProps
              .add(decisionState.chooserMoves[choice].inputProposition);

          //System.out.println("Encountered terminal state with goal value: "+ resultVector.scores.get(resultVector.controllingRole));
          if (getGoal(decisionState.choosingRole) == 100)
          {
            if (playedMoves != null)
            {
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

      if (playedMoves != null)
      {
        playedMoves.add(decisionState.chooserMoves[choice]);
      }

      decisionState.nextChoiceIndex = choiceIndex;
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
      for (int roleIndex = 0; roleIndex < getRoles().size(); roleIndex++)
      {
        chosenJointMoveProps[roleIndex] = decisionState.nonChooserProps[roleIndex];
        //System.out.println("Non chooser " + roleIndex + ": " + chosenJointMoveProps[roleIndex]);
      }
      transitionToNextStateFromChosenMove(null, null);
      //System.out.println("State: " + mungedState(lastInternalSetState));

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
    for (Role role : getRoles())
    {
      Collection<ForwardDeadReckonLegalMoveInfo> moves = propNet.getActiveLegalProps(instanceId).getContents(role);

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
    //		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.chooseRandomJointMove");
    //		try
    //		{
    int result = 0;
    int index = 0;

    for (Role role : getRoles())
    {
      Collection<ForwardDeadReckonLegalMoveInfo> moves = propNet.getActiveLegalProps(instanceId).getContents(role);
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
            System.out.println("Unexpected 0 move weight");
          }
          total += moveWeights.weightScore[info.globalMoveIndex];
        }

        if (total == 0)
        {
          System.out.println("Unexpected 0 move weight total");
        }
        rand = getRandom((int)total);
      }

      if (numChoices > result)
      {
        result = numChoices;
      }

      ForwardDeadReckonLegalMoveInfo chosen = null;
      while (chosen == null)
      {
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
      }

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
    //        }
    //		finally
    //		{
    //			methodSection.exitScope();
    //		}
  }

  private class TerminalResultVector
  {
    public Map<Role, Integer>             scores;
    public Role                           controllingRole;
    ForwardDeadReckonInternalMachineState state;

    public TerminalResultVector(Role controllingRole)
    {
      this.controllingRole = controllingRole;
      scores = new HashMap<Role, Integer>();
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
      for (Entry<GdlSentence, PolymorphicProposition> e : propNetX
          .getBasePropositions().entrySet())
      {
        ForwardDeadReckonProposition oProp = (ForwardDeadReckonProposition)propNetOWithoutGoals
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonProposition xProp = (ForwardDeadReckonProposition)propNetXWithoutGoals
            .getBasePropositions().get(e.getKey());
        ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)((ForwardDeadReckonProposition)e
            .getValue()).getInfo();

        info.xNetProp = xProp;
        info.oNetProp = oProp;

        xProp.setInfo(info);
        oProp.setInfo(info);
      }

      propNetXWithoutGoals.crystalize(masterInfoSet, maxInstances);
      propNetOWithoutGoals.crystalize(masterInfoSet, maxInstances);
      propNetXWithoutGoals.reset(true);
      propNetOWithoutGoals.reset(true);

      List<ForwardDeadReckonLegalMoveInfo> allMoves = new ArrayList<ForwardDeadReckonLegalMoveInfo>();
      for (ForwardDeadReckonLegalMoveInfo info : propNetXWithoutGoals
          .getMasterMoveList())
      {
        if (!allMoves.contains(info))
        {
          info.globalMoveIndex = allMoves.size();
          allMoves.add(info);
        }
      }
      for (ForwardDeadReckonLegalMoveInfo info : propNetOWithoutGoals
          .getMasterMoveList())
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
      for(ForwardDeadReckonLegalMoveInfo info : allMovesInfo)
      {
        for(Factor factor : factors)
        {
          if ( factor.getMoves().contains(info.move))
          {
            info.factor = factor;
            break;
          }
        }
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

  public int getDepthChargeResult(ForwardDeadReckonInternalMachineState state,
                                  Factor factor,
                                  Role role,
                                  final int[] stats,
                                  MoveWeights moveWeights,
                                  List<ForwardDeadReckonLegalMoveInfo> playedMoves)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    rolloutDepth = 0;
    TerminalResultSet resultSet = ((enableGreedyRollouts && getRoles().size() <= 2) ? new TerminalResultSet(getRoles())
                                                                                   : null);
    Map<Role, Integer> scores = new HashMap<Role, Integer>();

    if (validationMachine != null)
    {
      validationState = state.getMachineState();
    }
    setPropNetUsage(state);
    setBasePropositionsFromState(state, factor, true);
    for (int i = 0; i < roles.size(); i++)
    {
      previouslyChosenJointMovePropsX[i] = null;
      previouslyChosenJointMovePropsO[i] = null;
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

      //System.out.println("Rollout result after " + rolloutDepth + " steps:");
      for (Role r : roles)
      {
        //System.out.println("Role " + r + ": " + getGoal(r));
        scores.put(r, getGoal(r));
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

      for (Role r : roles)
      {
        scores.put(r, resultSet.resultVector.scores.get(r));
      }

      if (resultSet.resultVector.controllingRole != null)
      {
        setPropNetUsage(resultSet.resultVector.state);
        setBasePropositionsFromState(resultSet.resultVector.state, null, true);
      }
    }
    for (int i = 0; i < roles.size(); i++)
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

    return scores.get(role);
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
            ((ForwardDeadReckonProposition)p).setValue(false, instanceId);
          }
          for (ForwardDeadReckonPropositionInfo s : state)
          {
            ForwardDeadReckonPropositionCrossReferenceInfo scr = (ForwardDeadReckonPropositionCrossReferenceInfo)s;
            if (scr.goalsNetProp != null)
            {
              scr.goalsNetProp.setValue(true, instanceId);
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
                goalsNetProp.setValue(true, instanceId);
              }
              else
              {
                goalsNetProp.setValue(false, instanceId);
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
      ForwardDeadReckonComponent goalInput = (ForwardDeadReckonComponent)p
          .getSingleInput();
      if (goalInput != null && goalInput.getValue(instanceId))
      {
        result = Integer.parseInt(p.getName().getBody().get(1).toString());
        break;
      }
    }

    return result;
  }
}