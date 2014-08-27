package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.GoalsCalculator;

/**
 * @author steve
 * Subclass of MajorityCalculator that calculates the count from an ordered list
 * of base propositions which form a sequence (normally expecting only one to
 * be true at once - the count of the lowest which is true is returned)
 */
public class MajorityCountGoalsCalculator extends MajorityCalculator
{
  private final ForwardDeadReckonPropositionInfo[][] scoredPropositions;

  private MajorityCountGoalsCalculator(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                       Role xiOurRole,
                                       Map<Role, List<ForwardDeadReckonPropositionInfo>> xiScoredPropositions)
  {
    super(xiStateMachine, xiOurRole);

    assert(xiStateMachine.getRoles().length==2);

    scoredPropositions = new ForwardDeadReckonPropositionInfo[2][];

    for(Role role : xiStateMachine.getRoles())
    {
      List<ForwardDeadReckonPropositionInfo> scoredPropsList = xiScoredPropositions.get(role);
      int roleIndex = (role.equals(xiOurRole) ? 0 : 1);

      scoredPropositions[roleIndex] = new ForwardDeadReckonPropositionInfo[scoredPropsList.size()];
      for(int i = 0; i < scoredPropsList.size(); i++)
      {
        scoredPropositions[roleIndex][i] = scoredPropsList.get(i);
      }
    }
  }

  /**
   * Factory to produce a suitable goals calculator given a supporting set of role goals propositions
   * @param stateMachine - underlying state machine
   * @param xiOurRole - our role in this game
   * @param roleGoalSupportingSets - the set of supporting base props for each role's goals
   * @return goals calculator candidate, or null if no such is generatable
   */
  public static MajorityCalculator createMajorityCountGoalsCalculator(ForwardDeadReckonPropnetStateMachine stateMachine,
                                                                      Role xiOurRole,
                                                                      Map<Role, Set<PolymorphicProposition>> roleGoalSupportingSets)
  {
    Map<Role, List<ForwardDeadReckonPropositionInfo>> scoredPropositions = new HashMap<>();

    //  Do the goal supporting props form a sequence, transitionable in only one direction?
    for(Entry<Role, Set<PolymorphicProposition>> e : roleGoalSupportingSets.entrySet())
    {
      List<PolymorphicProposition> orderedPropositions = new ArrayList<>();

      for(PolymorphicProposition p : e.getValue())
      {
        if ( !orderedPropositions.contains(p))
        {
          if ( !appendPreceedingPropositions(stateMachine.getFullPropNet(), p, e.getValue(), orderedPropositions, null) )
          {
            return null;
          }
        }
      }

      //  We have managed to order the role's score props - now build a reverse dictionary
      List<ForwardDeadReckonPropositionInfo> scoredRolePropositions = new ArrayList<>();

      for(PolymorphicProposition p : orderedPropositions)
      {
        ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
        scoredRolePropositions.add(fdrp.getInfo());
      }

      scoredPropositions.put(e.getKey(), scoredRolePropositions);
    }

    return new MajorityCountGoalsCalculator(stateMachine, xiOurRole, scoredPropositions);
  }

  private static boolean appendPreceedingPropositions(PolymorphicPropNet pn,
                                                      PolymorphicProposition p,
                                                      Set<PolymorphicProposition> scoreProps,
                                                      List<PolymorphicProposition> orderedList,
                                                      Set<PolymorphicComponent> queuedBases)
  {
    if ( p.getSingleInput() instanceof PolymorphicTransition )
    {
      Set<PolymorphicProposition> baseProps = new HashSet<>();
      Set<PolymorphicComponent> processedComponents = new HashSet<>();

      if ( queuedBases == null )
      {
        queuedBases = new HashSet<>();
      }

      MajorityGoalsHeuristic.recursiveFindFeedingBaseProps(pn, p.getSingleInput().getSingleInput(), baseProps, processedComponents);

      queuedBases.add(p);
      for(PolymorphicProposition supportingProp : baseProps)
      {
        if ( supportingProp != p && scoreProps.contains(supportingProp))
        {
          if ( !orderedList.contains(supportingProp) )
          {
            if ( queuedBases.contains(supportingProp) || !appendPreceedingPropositions(pn, supportingProp, scoreProps, orderedList, queuedBases))
            {
              return false;
            }
          }
        }
      }
      queuedBases.remove(p);
    }

    if ( orderedList.contains(p))
    {
      //  Loop!
      return false;
    }

    orderedList.add(p);

    return true;
  }

  @Override
  public GoalsCalculator createThreadSafeReference()
  {
    return this;
  }

  @Override
  public void reverseRoles()
  {
    ForwardDeadReckonPropositionInfo[] temp = scoredPropositions[0];
    scoredPropositions[0] = scoredPropositions[1];
    scoredPropositions[1] = temp;
  }

  @Override
  public String getName()
  {
    return "Sequential counter";
  }

  @Override
  protected int getCount(ForwardDeadReckonInternalMachineState xiState, Role xiRole)
  {
    if ( xiRole.equals(ourRole ))
    {
      return getCount(xiState, 0);
    }

    return getCount(xiState, 1);
  }

  private int getCount(ForwardDeadReckonInternalMachineState xiState, int roleIndex)
  {
    int value = 0;

    for(ForwardDeadReckonPropositionInfo info : scoredPropositions[roleIndex])
    {
      if ( xiState.contains(info))
      {
        break;
      }

      value++;
    }

    return value;
  }

  @Override
  public boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState)
  {
    //  In a game where the scores are based on having the majority of a fixed
    //  total, and counts cannot go backwards (which the dependency analysis implies)
    //  then scores are latched once someone has more than half
    if ( isFixedSum )
    {
      return (getCount(xiState,0) > valueTotal/2 || getCount(xiState,1) > valueTotal/2);
    }

    return false;
  }

  @Override
  public Heuristic getDerivedHeuristic()
  {
    // TODO derived heuristic is dependent on goal stability
    return null;
  }
}
