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

public class MajorityCountGoalsCalculator extends MajorityCalculator
{
  private final Map<Role, List<ForwardDeadReckonPropositionInfo>> scoredPropositions;

  private MajorityCountGoalsCalculator(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                       Map<Role, List<ForwardDeadReckonPropositionInfo>> xiScoredPropositions)
  {
    super(xiStateMachine);
    scoredPropositions = xiScoredPropositions;
  }

  /**
   * Factory to produce a suitable goals calculator given a supporting set of role goals propositions
   * @param roleGoalSupportingSets - the set of supporting base props for each role's goals
   * @return goals calculator candidate, or null if no such is generatable
   */
  public static MajorityCalculator createMajorityCountGoalsCalculator(ForwardDeadReckonPropnetStateMachine stateMachine,
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

    return new MajorityCountGoalsCalculator(stateMachine, scoredPropositions);
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
    assert(scoredPropositions.size()==2);

    Role firstRole = null;
    Role secondRole = null;
    List<ForwardDeadReckonPropositionInfo> firstList = null;
    List<ForwardDeadReckonPropositionInfo> secondList = null;

    for(Entry<Role, List<ForwardDeadReckonPropositionInfo>> e : scoredPropositions.entrySet())
    {
      if ( firstRole == null )
      {
        firstRole = e.getKey();
        firstList = e.getValue();
      }
      else
      {
        secondRole = e.getKey();
        secondList = e.getValue();
      }
    }

    scoredPropositions.clear();
    scoredPropositions.put(firstRole, secondList);
    scoredPropositions.put(secondRole, firstList);
  }

  @Override
  public String getName()
  {
    return "Sequential counter";
  }

  @Override
  protected int getCount(ForwardDeadReckonInternalMachineState xiState, Role xiRole)
  {
    int value = 0;

    for(ForwardDeadReckonPropositionInfo info : scoredPropositions.get(xiRole))
    {
      if ( xiState.contains(info))
      {
        break;
      }

      value++;
    }

    return value;
  }
}
