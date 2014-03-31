
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.List;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public class ForwardDeadReckonPropNet extends PolymorphicPropNet
{
  /**
   * Creates a new PropNet from a list of Components, along with indices over
   * those components.
   *
   * @param components
   *          A list of Components.
   */
  private ForwardDeadReckonComponent[][]          propagationQueue          = null;
  private ForwardDeadReckonComponent[][]          alternatePropagationQueue = null;
  private int[]                                   propagationQueueIndex;
  private ForwardDeadReckonLegalMoveSet[]         activeLegalMoves;
  private ForwardDeadReckonLegalMoveSet           alwaysTrueLegalMoves;
  private ForwardDeadReckonInternalMachineState[] activeBasePropositions;
  private ForwardDeadReckonInternalMachineState   alwaysTrueBasePropositions;
  private int                                     numInstances;

  public ForwardDeadReckonPropNet(PropNet sourcePropnet,
                                  PolymorphicComponentFactory componentFactory)
  {
    super(sourcePropnet, componentFactory);

    propagationQueue = new ForwardDeadReckonComponent[1][getComponents()
        .size()];
    alternatePropagationQueue = new ForwardDeadReckonComponent[1][getComponents()
        .size()];
    propagationQueueIndex = new int[1];
    propagationQueueIndex[0] = 0;
  }

  public ForwardDeadReckonPropNet(PolymorphicPropNet sourcePropnet,
                                  PolymorphicComponentFactory componentFactory)
  {
    super(sourcePropnet, componentFactory);

    propagationQueue = new ForwardDeadReckonComponent[1][getComponents()
        .size()];
    alternatePropagationQueue = new ForwardDeadReckonComponent[1][getComponents()
        .size()];
    propagationQueueIndex = new int[1];
    propagationQueueIndex[0] = 0;
  }

  public ForwardDeadReckonPropNet(List<Role> roles,
                                  Set<PolymorphicComponent> components,
                                  PolymorphicComponentFactory componentFactory)
  {
    super(roles, components, componentFactory);

    propagationQueue = new ForwardDeadReckonComponent[1][getComponents()
        .size()];
    alternatePropagationQueue = new ForwardDeadReckonComponent[1][getComponents()
        .size()];
    propagationQueueIndex = new int[1];
    propagationQueueIndex[0] = 0;
  }

  private void setUpActivePropositionSets(ForwardDeadReckonPropositionInfo[] masterInfoSet)
  {
    activeLegalMoves = new ForwardDeadReckonLegalMoveSet[numInstances];
    alwaysTrueLegalMoves = new ForwardDeadReckonLegalMoveSet(getRoles());

    for (int instanceId = 0; instanceId < numInstances; instanceId++)
    {
      activeLegalMoves[instanceId] = new ForwardDeadReckonLegalMoveSet(alwaysTrueLegalMoves);
    }

    int roleIndex = 0;

    for (Role role : getRoles())
    {
      PolymorphicProposition[] legalProps = getLegalPropositions().get(role);

      for (PolymorphicProposition p : legalProps)
      {
        ForwardDeadReckonProposition pfdr = (ForwardDeadReckonProposition)p;
        ForwardDeadReckonLegalMoveInfo info = new ForwardDeadReckonLegalMoveInfo();

        info.move = new Move(pfdr.getName().getBody().get(1));
        info.inputProposition = (ForwardDeadReckonProposition)getLegalInputMap()
            .get(p);
        info.roleIndex = roleIndex;
        info.masterIndex = alwaysTrueLegalMoves.resolveId(info);

        PolymorphicComponent propInput = p.getSingleInput();
        if (propInput instanceof PolymorphicConstant)
        {
          if (((PolymorphicConstant)propInput).getValue())
          {
            alwaysTrueLegalMoves.add(info);
          }
        }
        else
        {
          for (int instanceId = 0; instanceId < numInstances; instanceId++)
          {
            pfdr.setTransitionSet(info,
                                  instanceId,
                                  activeLegalMoves[instanceId]);
          }
        }
      }

      roleIndex++;
    }

    activeBasePropositions = new ForwardDeadReckonInternalMachineState[numInstances];
    alwaysTrueBasePropositions = new ForwardDeadReckonInternalMachineState(masterInfoSet);

    for (int instanceId = 0; instanceId < numInstances; instanceId++)
    {
      activeBasePropositions[instanceId] = new ForwardDeadReckonInternalMachineState(masterInfoSet);

      for (PolymorphicProposition p : getBasePropositions().values())
      {
        PolymorphicComponent input = p.getSingleInput();

        if (input instanceof ForwardDeadReckonTransition)
        {
          ForwardDeadReckonTransition t = (ForwardDeadReckonTransition)input;

          t.setTransitionSet(((ForwardDeadReckonProposition)p)
                                 .getInfo(),
                             instanceId,
                             activeBasePropositions[instanceId]);
        }
        else if (instanceId == 0 && input instanceof PolymorphicConstant)
        {
          if (input.getValue())
          {
            alwaysTrueBasePropositions.add(((ForwardDeadReckonProposition)p)
                .getInfo());
          }
        }
      }
    }
  }

  public void crystalize(ForwardDeadReckonPropositionInfo[] masterInfoSet)
  {
    for (PolymorphicComponent c : getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      fdrc.crystalize(numInstances);
      fdrc.setPropnet(this);
    }

    setUpActivePropositionSets(masterInfoSet);

    propagationQueue = new ForwardDeadReckonComponent[numInstances][getComponents()
        .size()];
    alternatePropagationQueue = new ForwardDeadReckonComponent[numInstances][getComponents()
        .size()];
    propagationQueueIndex = new int[numInstances];

    for (int instanceId = 0; instanceId < numInstances; instanceId++)
    {
      propagationQueueIndex[instanceId] = 0;
    }
  }

  public void crystalize(ForwardDeadReckonPropositionInfo[] masterInfoSet,
                         int numInstances)
  {
    this.numInstances = numInstances;

    crystalize(masterInfoSet);
  }

  public ForwardDeadReckonLegalMoveSet getActiveLegalProps(int instanceId)
  {
    return activeLegalMoves[instanceId];
  }

  public ForwardDeadReckonInternalMachineState getActiveBaseProps(int instanceId)
  {
    return activeBasePropositions[instanceId];
  }

  public List<ForwardDeadReckonLegalMoveInfo> getMasterMoveList()
  {
    return alwaysTrueLegalMoves.getMasterList();
  }

  public void reset(boolean fullEquilibrium)
  {
    for (int instanceId = 0; instanceId < numInstances; instanceId++)
    {
      activeBasePropositions[instanceId].clear();
      activeBasePropositions[instanceId].merge(alwaysTrueBasePropositions);

      if (activeLegalMoves != null)
      {
        activeLegalMoves[instanceId].clear();
        activeLegalMoves[instanceId].merge(alwaysTrueLegalMoves);
      }

      for (PolymorphicComponent c : getComponents())
      {
        ((ForwardDeadReckonComponent)c).reset(instanceId);
      }
      //	Establish full reset state if required
      if (fullEquilibrium)
      {
        for (PolymorphicComponent c : getComponents())
        {
          ((ForwardDeadReckonComponent)c).queuePropagation(instanceId);
        }
        propagate(instanceId);
      }
    }
  }

  public void addToPropagateQueue(ForwardDeadReckonComponent component,
                                  int instanceId)
  {
    propagationQueue[instanceId][propagationQueueIndex[instanceId]++] = component;
  }

  private void validate()
  {
    for (PolymorphicComponent c : getComponents())
    {
      ((ForwardDeadReckonComponent)c).validate();
    }
  }

  public void propagate(int instanceId)
  {
    ProfileSection methodSection = new ProfileSection("ForwardDeadReckonPropNet.propagate");
    try
    {
      while (propagationQueueIndex[instanceId] > 0)
      {
        //validate();

        ForwardDeadReckonComponent[] queue = propagationQueue[instanceId];
        int queueSize = propagationQueueIndex[instanceId];

        propagationQueue[instanceId] = alternatePropagationQueue[instanceId];
        alternatePropagationQueue[instanceId] = queue;

        propagationQueueIndex[instanceId] = 0;

        for (int i = 0; i < queueSize; i++)
        {
          queue[i].propagate(instanceId);
        }
      }
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public PolymorphicProposition findProposition(GdlSentence queryProposition)
  {
    for (PolymorphicProposition p : getPropositions())
    {
      if (p.getName() == queryProposition)
      {
        return p;
      }
    }

    return null;
  }
}
