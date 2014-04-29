
package org.ggp.base.util.propnet.polymorphic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlProposition;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.And;
import org.ggp.base.util.propnet.architecture.components.Constant;
import org.ggp.base.util.propnet.architecture.components.Not;
import org.ggp.base.util.propnet.architecture.components.Or;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.architecture.components.Transition;
import org.ggp.base.util.propnet.polymorphic.factory.OptimizingPolymorphicPropNetFactory;
import org.ggp.base.util.statemachine.Role;


/**
 * The PolymorphicPropNet class is an instantiation vehicle for propnets using
 * polymorphic components. It constructs itself from a provided input propnet
 * (either another polymorphic one, or a basic one of class PropNet) by copying
 * the topology onto a component set created by the provided component factory,
 * preserving ordering of inputs and outputs subject to the components
 * concerned guaranteeing a meaningful enumeration order of those collections
 * and add adding to the end (if they care about order)
 */

public class PolymorphicPropNet
{
  /** References to every component in the PropNet. */
  private final Set<PolymorphicComponent>                           components;

  /** References to every Proposition in the PropNet. */
  private final Set<PolymorphicProposition>                         propositions;

  /** References to every BaseProposition in the PropNet, indexed by name. */
  private final Map<GdlSentence, PolymorphicProposition>            basePropositions;
  private PolymorphicProposition[]                                  basePropositionsArray;

  /** References to every InputProposition in the PropNet, indexed by name. */
  private final Map<GdlSentence, PolymorphicProposition>            inputPropositions;

  /** References to every LegalProposition in the PropNet, indexed by role. */
  private final Map<Role, Set<PolymorphicProposition>>              legalPropositionsMutable;
  private Map<Role, PolymorphicProposition[]>                       legalPropositions;

  /** References to every GoalProposition in the PropNet, indexed by role. */
  private Map<Role, Set<PolymorphicProposition>>                    goalPropositionsMutable;
  private Map<Role, PolymorphicProposition[]>                       goalPropositions;

  /** A reference to the single, unique, InitProposition. */
  private PolymorphicProposition                                    initProposition;

  /** A reference to the single, unique, TerminalProposition. */
  private final PolymorphicProposition                              terminalProposition;

  /** A helper mapping between input/legal propositions. */
  private final Map<PolymorphicProposition, PolymorphicProposition> legalInputMap;

  /** A helper list of all of the roles. */
  private final List<Role>                                          roles;

  private PolymorphicComponentFactory                               componentFactory;

  /**
   * Creates a new PropNet from a list of Components, along with indices over
   * those components.
   *
   * @param components
   *          A list of Components.
   */
  public PolymorphicPropNet(List<Role> roles,
                            Set<PolymorphicComponent> components,
                            PolymorphicComponentFactory componentFactory)
  {
    this.componentFactory = componentFactory;
    this.roles = roles;
    this.components = components;
    this.propositions = recordPropositions();
    this.basePropositions = recordBasePropositions();
    this.inputPropositions = recordInputPropositions();
    this.legalPropositions = null;
    this.legalPropositionsMutable = recordLegalPropositions();
    goalPropositions = null;
    this.goalPropositionsMutable = recordGoalPropositions();
    this.initProposition = recordInitProposition();
    this.terminalProposition = recordTerminalProposition();
    this.legalInputMap = makeLegalInputMap();
  }

  private Map<PolymorphicProposition, PolymorphicProposition> makeLegalInputMap()
  {
    Map<PolymorphicProposition, PolymorphicProposition> legalInputMap = new HashMap<PolymorphicProposition, PolymorphicProposition>();
    // Create a mapping from Body->Input.
    Map<List<GdlTerm>, PolymorphicProposition> inputPropsByBody = new HashMap<List<GdlTerm>, PolymorphicProposition>();
    for (PolymorphicProposition inputProp : inputPropositions.values())
    {
      List<GdlTerm> inputPropBody = (inputProp.getName()).getBody();
      inputPropsByBody.put(inputPropBody, inputProp);
    }
    // Use that mapping to map Input->Legal and Legal->Input
    // based on having the same Body proposition.
    for (Set<PolymorphicProposition> legalProps : legalPropositionsMutable
        .values())
    {
      for (PolymorphicProposition legalProp : legalProps)
      {
        List<GdlTerm> legalPropBody = (legalProp.getName()).getBody();
        if (inputPropsByBody.containsKey(legalPropBody))
        {
          PolymorphicProposition inputProp = inputPropsByBody
              .get(legalPropBody);
          legalInputMap.put(inputProp, legalProp);
          legalInputMap.put(legalProp, inputProp);
        }
      }
    }
    return legalInputMap;
  }

  /**
   * Builds an index over the BasePropositions in the PropNet. This is done by
   * going over every single-input proposition in the network, and seeing
   * whether or not its input is a transition, which would mean that by
   * definition the proposition is a base proposition.
   *
   * @return An index over the BasePropositions in the PropNet.
   */
  private Map<GdlSentence, PolymorphicProposition> recordBasePropositions()
  {
    Map<GdlSentence, PolymorphicProposition> basePropositions = new HashMap<GdlSentence, PolymorphicProposition>();
    for (PolymorphicProposition proposition : propositions)
    {
      // Skip all propositions without exactly one input.
      if (proposition.getInputs().size() != 1)
        continue;

      PolymorphicComponent component = proposition.getSingleInput();
      if (component instanceof PolymorphicTransition)
      {
        basePropositions.put(proposition.getName(), proposition);
      }
    }

    return basePropositions;
  }

  /**
   * Builds an index over the GoalPropositions in the PropNet. This is done by
   * going over every function proposition in the network where the name of the
   * function is "goal", and extracting the name of the role associated with
   * that goal proposition, and then using those role names as keys that map to
   * the goal propositions in the index.
   *
   * @return An index over the GoalPropositions in the PropNet.
   */
  private Map<Role, Set<PolymorphicProposition>> recordGoalPropositions()
  {
    goalPropositionsMutable = new HashMap<Role, Set<PolymorphicProposition>>();
    for (PolymorphicProposition proposition : propositions)
    {
      // Skip all propositions that aren't GdlRelations.
      if (!(proposition.getName() instanceof GdlRelation))
        continue;

      GdlRelation relation = (GdlRelation)proposition.getName();
      if (!relation.getName().getValue().equals("goal"))
        continue;

      Role theRole = new Role((GdlConstant)relation.get(0));
      if (!goalPropositionsMutable.containsKey(theRole))
      {
        goalPropositionsMutable.put(theRole,
                                    new HashSet<PolymorphicProposition>());
      }
      goalPropositionsMutable.get(theRole).add(proposition);
    }

    return goalPropositionsMutable;
  }

  /**
   * Returns a reference to the single, unique, InitProposition.
   *
   * @return A reference to the single, unique, InitProposition.
   */
  private PolymorphicProposition recordInitProposition()
  {
    for (PolymorphicProposition proposition : propositions)
    {
      // Skip all propositions that aren't GdlPropositions.
      if (!(proposition.getName() instanceof GdlProposition))
        continue;

      GdlConstant constant = ((GdlProposition)proposition.getName()).getName();
      if (constant.getValue().toUpperCase().equals("INIT"))
      {
        return proposition;
      }
    }
    return null;
  }

  /**
   * Builds an index over the InputPropositions in the PropNet.
   *
   * @return An index over the InputPropositions in the PropNet.
   */
  private Map<GdlSentence, PolymorphicProposition> recordInputPropositions()
  {
    Map<GdlSentence, PolymorphicProposition> inputPropositions = new HashMap<GdlSentence, PolymorphicProposition>();
    for (PolymorphicProposition proposition : propositions)
    {
      // Skip all propositions that aren't GdlFunctions.
      if (!(proposition.getName() instanceof GdlRelation))
        continue;

      GdlRelation relation = (GdlRelation)proposition.getName();
      if (relation.getName().getValue().equals("does"))
      {
        inputPropositions.put(proposition.getName(), proposition);
      }
    }

    return inputPropositions;
  }

  /**
   * Builds an index over the LegalPropositions in the PropNet.
   *
   * @return An index over the LegalPropositions in the PropNet.
   */
  private Map<Role, Set<PolymorphicProposition>> recordLegalPropositions()
  {
    Map<Role, Set<PolymorphicProposition>> legalPropositions = new HashMap<Role, Set<PolymorphicProposition>>();
    for (PolymorphicProposition proposition : propositions)
    {
      // Skip all propositions that aren't GdlRelations.
      if (!(proposition.getName() instanceof GdlRelation))
        continue;

      GdlRelation relation = (GdlRelation)proposition.getName();
      if (relation.getName().getValue().equals("legal"))
      {
        GdlConstant name = (GdlConstant)relation.get(0);
        Role r = new Role(name);
        if (!legalPropositions.containsKey(r))
        {
          legalPropositions.put(r, new HashSet<PolymorphicProposition>());
        }
        legalPropositions.get(r).add(proposition);
      }
    }

    return legalPropositions;
  }

  /**
   * Builds an index over the Propositions in the PropNet.
   *
   * @return An index over Propositions in the PropNet.
   */
  private Set<PolymorphicProposition> recordPropositions()
  {
    Set<PolymorphicProposition> propositions = new HashSet<PolymorphicProposition>();
    for (PolymorphicComponent component : components)
    {
      if (component instanceof PolymorphicProposition)
      {
        propositions.add((PolymorphicProposition)component);
      }
    }
    return propositions;
  }

  /**
   * Records a reference to the single, unique, TerminalProposition.
   *
   * @return A reference to the single, unqiue, TerminalProposition.
   */
  private PolymorphicProposition recordTerminalProposition()
  {
    for (PolymorphicProposition proposition : propositions)
    {
      if (proposition.getName() instanceof GdlProposition)
      {
        GdlConstant constant = ((GdlProposition)proposition.getName())
            .getName();
        if (constant.getValue().equals("terminal"))
        {
          return proposition;
        }
      }
    }

    return null;
  }

  /**
   * Creates a new PropNet from a list of Components, along with indices over
   * those components.
   *
   * @param components
   *          A list of Components.
   */
  public PolymorphicPropNet(PropNet sourcePropnet,
                            PolymorphicComponentFactory componentFactory)
  {
    this.componentFactory = componentFactory;

    Map<Component, PolymorphicComponent> sourceToTargetMap = new HashMap<Component, PolymorphicComponent>();

    components = new HashSet<PolymorphicComponent>();

    //	Create the components
    for (Component old : sourcePropnet.getComponents())
    {
      PolymorphicComponent newComp;

      if (old instanceof And)
      {
        newComp = componentFactory.createAnd(old.getInputs().size(), old
            .getOutputs().size());
      }
      else if (old instanceof Or)
      {
        newComp = componentFactory.createOr(old.getInputs().size(), old
            .getOutputs().size());
      }
      else if (old instanceof Not)
      {
        newComp = componentFactory.createNot(old.getOutputs().size());
      }
      else if (old instanceof Proposition)
      {
        newComp = componentFactory.createProposition(old.getOutputs().size(),
                                                     ((Proposition)old)
                                                         .getName());
      }
      else if (old instanceof Transition)
      {
        newComp = componentFactory.createTransition(old.getOutputs().size());
      }
      else if (old instanceof Constant)
      {
        newComp = componentFactory.createConstant(old.getOutputs().size(),
                                                  ((Constant)old).getValue());
      }
      else
      {
        throw new RuntimeException("Invalid propnet");
      }

      sourceToTargetMap.put(old, newComp);
      components.add(newComp);
    }

    //	Connect them up
    for (Component old : sourcePropnet.getComponents())
    {
      PolymorphicComponent newComp = sourceToTargetMap.get(old);

      for (Component oldInput : old.getInputs())
      {
        PolymorphicComponent newInput = sourceToTargetMap.get(oldInput);

        newComp.addInput(newInput);
      }

      for (Component oldOutput : old.getOutputs())
      {
        PolymorphicComponent newOutput = sourceToTargetMap.get(oldOutput);

        newComp.addOutput(newOutput);
      }
    }

    //	Construct the various maps and collections we need to supply
    propositions = new HashSet<PolymorphicProposition>();
    for (Proposition oldProp : sourcePropnet.getPropositions())
    {
      PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
          .get(oldProp);

      propositions.add(newProp);
    }
    basePropositions = new HashMap<GdlSentence, PolymorphicProposition>();
    for (Entry<GdlSentence, Proposition> oldEntry : sourcePropnet
        .getBasePropositions().entrySet())
    {
      PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getValue());

      basePropositions.put(oldEntry.getKey(), newProp);
    }
    inputPropositions = new HashMap<GdlSentence, PolymorphicProposition>();
    for (Entry<GdlSentence, Proposition> oldEntry : sourcePropnet
        .getInputPropositions().entrySet())
    {
      PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getValue());

      inputPropositions.put(oldEntry.getKey(), newProp);
    }
    legalPropositions = null;
    legalPropositionsMutable = new HashMap<Role, Set<PolymorphicProposition>>();
    for (Entry<Role, Set<Proposition>> oldEntry : sourcePropnet
        .getLegalPropositions().entrySet())
    {
      Set<PolymorphicProposition> newProps = new HashSet<PolymorphicProposition>();

      for (Proposition oldProp : oldEntry.getValue())
      {
        PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
            .get(oldProp);

        newProps.add(newProp);
      }

      legalPropositionsMutable.put(oldEntry.getKey(), newProps);
    }
    goalPropositions = null;
    goalPropositionsMutable = new HashMap<Role, Set<PolymorphicProposition>>();
    for (Entry<Role, Set<Proposition>> oldEntry : sourcePropnet
        .getGoalPropositions().entrySet())
    {
      Set<PolymorphicProposition> newProps = new HashSet<PolymorphicProposition>();

      for (Proposition oldProp : oldEntry.getValue())
      {
        PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
            .get(oldProp);

        newProps.add(newProp);
      }

      goalPropositionsMutable.put(oldEntry.getKey(), newProps);
    }
    initProposition = (PolymorphicProposition)sourceToTargetMap
        .get(sourcePropnet.getInitProposition());
    terminalProposition = (PolymorphicProposition)sourceToTargetMap
        .get(sourcePropnet.getTerminalProposition());
    legalInputMap = new HashMap<PolymorphicProposition, PolymorphicProposition>();
    for (Entry<Proposition, Proposition> oldEntry : sourcePropnet
        .getLegalInputMap().entrySet())
    {
      PolymorphicProposition newProp1 = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getKey());
      PolymorphicProposition newProp2 = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getValue());

      legalInputMap.put(newProp1, newProp2);
    }

    roles = sourcePropnet.getRoles();
  }

  public PolymorphicPropNet(PolymorphicPropNet sourcePropnet,
                            PolymorphicComponentFactory componentFactory)
  {
    this.componentFactory = componentFactory;

    Map<PolymorphicComponent, PolymorphicComponent> sourceToTargetMap = new HashMap<PolymorphicComponent, PolymorphicComponent>();

    components = new HashSet<PolymorphicComponent>();

    //	Create the components
    for (PolymorphicComponent old : sourcePropnet.getComponents())
    {
      PolymorphicComponent newComp;

      if (old instanceof PolymorphicAnd)
      {
        newComp = componentFactory.createAnd(-1, -1);
      }
      else if (old instanceof PolymorphicOr)
      {
        newComp = componentFactory.createOr(-1, -1);
      }
      else if (old instanceof PolymorphicNot)
      {
        newComp = componentFactory.createNot(-1);
      }
      else if (old instanceof PolymorphicProposition)
      {
        newComp = componentFactory
            .createProposition(-1, ((PolymorphicProposition)old).getName());
      }
      else if (old instanceof PolymorphicTransition)
      {
        newComp = componentFactory.createTransition(-1);
      }
      else if (old instanceof PolymorphicConstant)
      {
        newComp = componentFactory.createConstant(-1,
                                                  ((PolymorphicConstant)old)
                                                      .getValue());
      }
      else
      {
        throw new RuntimeException("Invalid propnet");
      }

      sourceToTargetMap.put(old, newComp);
      components.add(newComp);
    }

    //	Connect them up
    for (PolymorphicComponent old : sourcePropnet.getComponents())
    {
      PolymorphicComponent newComp = sourceToTargetMap.get(old);

      for (PolymorphicComponent oldInput : old.getInputs())
      {
        PolymorphicComponent newInput = sourceToTargetMap.get(oldInput);

        newComp.addInput(newInput);
      }

      for (PolymorphicComponent oldOutput : old.getOutputs())
      {
        PolymorphicComponent newOutput = sourceToTargetMap.get(oldOutput);

        newComp.addOutput(newOutput);
      }
    }

    //	Construct the various maps and collections we need to supply
    propositions = new HashSet<PolymorphicProposition>();
    for (PolymorphicProposition oldProp : sourcePropnet.getPropositions())
    {
      PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
          .get(oldProp);

      propositions.add(newProp);
    }
    basePropositions = new HashMap<GdlSentence, PolymorphicProposition>();
    for (Entry<GdlSentence, PolymorphicProposition> oldEntry : sourcePropnet
        .getBasePropositions().entrySet())
    {
      PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getValue());

      basePropositions.put(oldEntry.getKey(), newProp);
    }
    inputPropositions = new HashMap<GdlSentence, PolymorphicProposition>();
    for (Entry<GdlSentence, PolymorphicProposition> oldEntry : sourcePropnet
        .getInputPropositions().entrySet())
    {
      PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getValue());

      inputPropositions.put(oldEntry.getKey(), newProp);
    }
    legalPropositions = null;
    legalPropositionsMutable = new HashMap<Role, Set<PolymorphicProposition>>();
    for (Entry<Role, PolymorphicProposition[]> oldEntry : sourcePropnet
        .getLegalPropositions().entrySet())
    {
      Set<PolymorphicProposition> newProps = new HashSet<PolymorphicProposition>();

      for (PolymorphicProposition oldProp : oldEntry.getValue())
      {
        PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
            .get(oldProp);

        newProps.add(newProp);
      }

      legalPropositionsMutable.put(oldEntry.getKey(), newProps);
    }
    goalPropositions = null;
    goalPropositionsMutable = new HashMap<Role, Set<PolymorphicProposition>>();
    for (Entry<Role, PolymorphicProposition[]> oldEntry : sourcePropnet
        .getGoalPropositions().entrySet())
    {
      Set<PolymorphicProposition> newProps = new HashSet<PolymorphicProposition>();

      for (PolymorphicProposition oldProp : oldEntry.getValue())
      {
        PolymorphicProposition newProp = (PolymorphicProposition)sourceToTargetMap
            .get(oldProp);

        newProps.add(newProp);
      }

      goalPropositionsMutable.put(oldEntry.getKey(), newProps);
    }
    initProposition = (PolymorphicProposition)sourceToTargetMap
        .get(sourcePropnet.getInitProposition());
    terminalProposition = (PolymorphicProposition)sourceToTargetMap
        .get(sourcePropnet.getTerminalProposition());
    legalInputMap = new HashMap<PolymorphicProposition, PolymorphicProposition>();
    for (Entry<PolymorphicProposition, PolymorphicProposition> oldEntry : sourcePropnet
        .getLegalInputMap().entrySet())
    {
      PolymorphicProposition newProp1 = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getKey());
      PolymorphicProposition newProp2 = (PolymorphicProposition)sourceToTargetMap
          .get(oldEntry.getValue());

      legalInputMap.put(newProp1, newProp2);
    }

    roles = sourcePropnet.getRoles();
  }

  public List<Role> getRoles()
  {
    return roles;
  }

  public Map<PolymorphicProposition, PolymorphicProposition> getLegalInputMap()
  {
    return legalInputMap;
  }

  /**
   * Getter method.
   *
   * @return References to every BaseProposition in the PropNet, indexed by
   *         name.
   */
  public Map<GdlSentence, PolymorphicProposition> getBasePropositions()
  {
    return basePropositions;
  }

  public PolymorphicProposition[] getBasePropositionsArray()
  {
    synchronized (this)
    {
      if (basePropositionsArray == null)
      {
        basePropositionsArray = new PolymorphicProposition[basePropositions.size()];
        int index = 0;
        for (PolymorphicProposition p : basePropositions.values())
        {
          basePropositionsArray[index++] = p;
        }
      }
      return basePropositionsArray;
    }
  }

  /**
   * Getter method.
   *
   * @return References to every Component in the PropNet.
   */
  public Set<PolymorphicComponent> getComponents()
  {
    return components;
  }

  /**
   * Getter method.
   *
   * @return References to every GoalProposition in the PropNet, indexed by
   *         player name.
   */
  public Map<Role, PolymorphicProposition[]> getGoalPropositions()
  {
    if (goalPropositions == null)
    {
      goalPropositions = new HashMap<Role, PolymorphicProposition[]>();
      for (Role role : goalPropositionsMutable.keySet())
      {
        PolymorphicProposition[] goalsForRole = new PolymorphicProposition[goalPropositionsMutable
            .get(role).size()];
        int index = 0;
        for (PolymorphicProposition p : goalPropositionsMutable.get(role))
        {
          goalsForRole[index++] = p;
        }

        goalPropositions.put(role, goalsForRole);
      }
    }
    return goalPropositions;
  }

  /**
   * Getter method. A reference to the single, unique, InitProposition.
   *
   * @return
   */
  public PolymorphicProposition getInitProposition()
  {
    return initProposition;
  }

  public void RemoveInits()
  {
    OptimizingPolymorphicPropNetFactory.removeInitPropositions(this);

    initProposition = null;
  }

  public void RemoveGoals()
  {
    OptimizingPolymorphicPropNetFactory.removeGoalPropositions(this);
  }

  public void RemoveAllButGoals()
  {
    RemoveInits();
    OptimizingPolymorphicPropNetFactory.removeAllButGoalPropositions(this);
  }

  /**
   * Getter method.
   *
   * @return References to every InputProposition in the PropNet, indexed by
   *         name.
   */
  public Map<GdlSentence, PolymorphicProposition> getInputPropositions()
  {
    return inputPropositions;
  }

  /**
   * Getter method.
   *
   * @return References to every LegalProposition in the PropNet, indexed by
   *         player name.
   */
  public Map<Role, PolymorphicProposition[]> getLegalPropositions()
  {
    if (legalPropositions == null)
    {
      legalPropositions = new HashMap<Role, PolymorphicProposition[]>();
      for (Role role : legalPropositionsMutable.keySet())
      {
        PolymorphicProposition[] legalsForRole = new PolymorphicProposition[legalPropositionsMutable
            .get(role).size()];
        int index = 0;
        for (PolymorphicProposition p : legalPropositionsMutable.get(role))
        {
          legalsForRole[index++] = p;
        }

        legalPropositions.put(role, legalsForRole);
      }

    }
    return legalPropositions;
  }

  /**
   * Getter method.
   *
   * @return References to every Proposition in the PropNet.
   */
  public Set<PolymorphicProposition> getPropositions()
  {
    return propositions;
  }

  /**
   * Getter method.
   *
   * @return A reference to the single, unique, TerminalProposition.
   */
  public PolymorphicProposition getTerminalProposition()
  {
    return terminalProposition;
  }

  /**
   * Returns a representation of the PropNet in .dot format.
   *
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();

    sb.append("digraph propNet\n{\n");
    for (PolymorphicComponent component : components)
    {
      sb.append("\t" + component.toString() + "\n");
    }
    sb.append("}");

    return sb.toString();
  }

  /**
   * Outputs the propnet in .dot format to a particular file. This can be
   * viewed with tools like Graphviz and ZGRViewer.
   *
   * @param filename
   *          the name of the file to output to
   */
  public void renderToFile(String filename)
  {
    try
    {
      File f = new File(filename);
      FileOutputStream fos = new FileOutputStream(f);
      OutputStreamWriter fout = new OutputStreamWriter(fos, "UTF-8");
      fout.write(toString());
      fout.close();
      fos.close();
    }
    catch (Exception e)
    {
      GamerLogger.logStackTrace("StateMachine", e);
    }
  }

  /**
   * Removes a component from the propnet. Be very careful when using this
   * method, as it is not thread-safe. It is highly recommended that this
   * method only be used in an optimization period between the propnet's
   * creation and its initial use, during which it should only be accessed by a
   * single thread. The INIT and terminal components cannot be removed.
   */
  public void removeComponent(PolymorphicComponent c)
  {
    //Go through all the collections it could appear in
    if (c instanceof PolymorphicProposition)
    {
      PolymorphicProposition p = (PolymorphicProposition)c;
      GdlSentence name = p.getName();
      if (basePropositions.containsKey(name))
      {
        basePropositionsArray = null;
        basePropositions.remove(name);
      }
      else if (inputPropositions.containsKey(name))
      {
        inputPropositions.remove(name);
        //The map goes both ways...
        PolymorphicProposition partner = legalInputMap.get(p);
        if (partner != null)
        {
          legalInputMap.remove(partner);
          legalInputMap.remove(p);
        }
      }
      else if (name == GdlPool.getProposition(GdlPool.getConstant("terminal")))
      {
        throw new RuntimeException("The terminal component cannot be removed.");
      }
      else
      {
        for (Set<PolymorphicProposition> propositions : legalPropositionsMutable
            .values())
        {
          if (propositions.contains(p))
          {
            legalPropositions = null;
            propositions.remove(p);
            PolymorphicProposition partner = legalInputMap.get(p);
            if (partner != null)
            {
              legalInputMap.remove(partner);
              legalInputMap.remove(p);
            }
          }
        }
        for (Set<PolymorphicProposition> propositions : goalPropositionsMutable
            .values())
        {
          goalPropositions = null;
          propositions.remove(p);
        }
      }
      propositions.remove(p);
    }
    components.remove(c);

    //Remove all the local links to the component
    for (PolymorphicComponent parent : c.getInputs())
      parent.removeOutput(c);
    for (PolymorphicComponent child : c.getOutputs())
      child.removeInput(c);
    //These are actually unnecessary...
    //c.removeAllInputs();
    //c.removeAllOutputs();
  }

  public void addComponent(PolymorphicComponent c)
  {
    components.add(c);
    if (c instanceof PolymorphicProposition)
      propositions.add((PolymorphicProposition)c);
  }

  public PolymorphicComponentFactory getComponentFactory()
  {
    return componentFactory;
  }

  public void crystalize()
  {
    for (PolymorphicComponent c : components)
    {
      c.crystalize();
    }
  }
}