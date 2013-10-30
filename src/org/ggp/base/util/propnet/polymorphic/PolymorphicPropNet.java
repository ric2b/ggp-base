package org.ggp.base.util.propnet.polymorphic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.And;
import org.ggp.base.util.propnet.architecture.components.Constant;
import org.ggp.base.util.propnet.architecture.components.Not;
import org.ggp.base.util.propnet.architecture.components.Or;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.architecture.components.Transition;
import org.ggp.base.util.statemachine.Role;


/**
 * The PolymorphicPropNet class is an instantiation vehicle for propnets
 * using polymorphic components.  It constructs itself from a provided input
 * propnet (either another polymorphic one, or a basic one of class PropNet)
 * by copying the topology onto a component set created by the provided
 * component factory, preserving ordering of inputs and outputs subject to
 * the components concerned guaranteeing a meaningful enumeration order of those
 * collections and add adding to the end (if they care about order)
 */

public final class PolymorphicPropNet
{
	/** References to every component in the PropNet. */
	private final Set<PolymorphicComponent> components;
	
	/** References to every Proposition in the PropNet. */
	private final Set<PolymorphicProposition> propositions;
	
	/** References to every BaseProposition in the PropNet, indexed by name. */
	private final Map<GdlSentence, PolymorphicProposition> basePropositions;
	private PolymorphicProposition[] basePropositionsArray;
	
	/** References to every InputProposition in the PropNet, indexed by name. */
	private final Map<GdlSentence, PolymorphicProposition> inputPropositions;
	
	/** References to every LegalProposition in the PropNet, indexed by role. */
	private final Map<Role, PolymorphicProposition[]> legalPropositions;
	
	/** References to every GoalProposition in the PropNet, indexed by role. */
	private final Map<Role, PolymorphicProposition[]> goalPropositions;
	
	/** A reference to the single, unique, InitProposition. */
	private final PolymorphicProposition initProposition;
	
	/** A reference to the single, unique, TerminalProposition. */
	private final PolymorphicProposition terminalProposition;
	
	/** A helper mapping between input/legal propositions. */
	private final Map<PolymorphicProposition, PolymorphicProposition> legalInputMap;
	
	/** A helper list of all of the roles. */
	private final List<Role> roles;

	/**
	 * Creates a new PropNet from a list of Components, along with indices over
	 * those components.
	 * 
	 * @param components
	 *            A list of Components.
	 */
	public PolymorphicPropNet(PropNet sourcePropnet, PolymorphicComponentFactory componentFactory)
	{
		Map<Component,PolymorphicComponent> sourceToTargetMap = new HashMap<Component,PolymorphicComponent>();
		
		components = new HashSet<PolymorphicComponent>();
		
		//	Create the components
		for(Component old : sourcePropnet.getComponents())
		{
			PolymorphicComponent newComp;

			if (old instanceof And)
			{
				newComp = componentFactory.createAnd(old.getInputs().size(), old.getOutputs().size());
			}
			else if (old instanceof Or)
			{
				newComp = componentFactory.createOr(old.getInputs().size(), old.getOutputs().size());
			}
			else if (old instanceof Not)
			{
				newComp = componentFactory.createNot(old.getOutputs().size());
			}
			else if (old instanceof Proposition)
			{
				newComp = componentFactory.createProposition(old.getOutputs().size(),((Proposition)old).getName());
			}
			else if (old instanceof Transition)
			{
				newComp = componentFactory.createTransition(old.getOutputs().size());
			}
			else if (old instanceof Constant)
			{
				newComp = componentFactory.createConstant(old.getOutputs().size(), ((Constant)old).getValue());
			}
			else
			{
				throw new RuntimeException("Invalid propnet");
			}
			
			sourceToTargetMap.put(old, newComp);
			components.add(newComp);
		}
		
		//	Connect them up
		for(Component old : sourcePropnet.getComponents())
		{
			PolymorphicComponent newComp = sourceToTargetMap.get(old);
			
			for(Component oldInput : old.getInputs())
			{
				PolymorphicComponent newInput = sourceToTargetMap.get(oldInput);
				
				newComp.addInput(newInput);
			}
			
			for(Component oldOutput : old.getOutputs())
			{
				PolymorphicComponent newOutput = sourceToTargetMap.get(oldOutput);
				
				newComp.addOutput(newOutput);
			}
		}
		
		//	Construct the various maps and collections we need to supply
		propositions = new HashSet<PolymorphicProposition>();
		for(Proposition oldProp : sourcePropnet.getPropositions())
		{
			PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldProp);
			
			propositions.add(newProp);
		}
		basePropositions = new HashMap<GdlSentence,PolymorphicProposition>();
		for(Entry<GdlSentence, Proposition> oldEntry : sourcePropnet.getBasePropositions().entrySet())
		{
			PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getValue());
			
			basePropositions.put(oldEntry.getKey(), newProp);
		}
		inputPropositions = new HashMap<GdlSentence,PolymorphicProposition>();
		for(Entry<GdlSentence, Proposition> oldEntry : sourcePropnet.getInputPropositions().entrySet())
		{
			PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getValue());
			
			inputPropositions.put(oldEntry.getKey(), newProp);
		}
		legalPropositions = new HashMap<Role, PolymorphicProposition[]>();
		for(Entry<Role, Set<Proposition>> oldEntry : sourcePropnet.getLegalPropositions().entrySet())
		{
			PolymorphicProposition newProps[] = new PolymorphicProposition[oldEntry.getValue().size()];
			int index = 0;
			
			for(Proposition oldProp : oldEntry.getValue())
			{
				PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldProp);
				
				newProps[index++] = newProp;
			}
			
			legalPropositions.put(oldEntry.getKey(), newProps);
		}
		goalPropositions = new HashMap<Role, PolymorphicProposition[]>();
		for(Entry<Role, Set<Proposition>> oldEntry : sourcePropnet.getGoalPropositions().entrySet())
		{
			PolymorphicProposition[] newProps = new PolymorphicProposition[oldEntry.getValue().size()];
			int index = 0;
			
			for(Proposition oldProp : oldEntry.getValue())
			{
				PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldProp);
				
				newProps[index++] = newProp;
			}
			
			goalPropositions.put(oldEntry.getKey(), newProps);
		}
		initProposition = (PolymorphicProposition) sourceToTargetMap.get(sourcePropnet.getInitProposition());
		terminalProposition = (PolymorphicProposition) sourceToTargetMap.get(sourcePropnet.getTerminalProposition());
		legalInputMap = new HashMap<PolymorphicProposition, PolymorphicProposition>();
		for(Entry<Proposition, Proposition> oldEntry : sourcePropnet.getLegalInputMap().entrySet())
		{
			PolymorphicProposition newProp1 = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getKey());
			PolymorphicProposition newProp2 = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getValue());
			
			legalInputMap.put(newProp1, newProp2);
		}
		
		roles = sourcePropnet.getRoles();
		
		basePropositionsArray = new PolymorphicProposition[basePropositions.size()];
		int index = 0;
		for(PolymorphicProposition p : basePropositions.values())
		{
			basePropositionsArray[index++] = p;
		}
	}
	
	public PolymorphicPropNet(PolymorphicPropNet sourcePropnet, PolymorphicComponentFactory componentFactory)
	{
		Map<PolymorphicComponent,PolymorphicComponent> sourceToTargetMap = new HashMap<PolymorphicComponent,PolymorphicComponent>();
		
		components = new HashSet<PolymorphicComponent>();
		
		//	Create the components
		for(PolymorphicComponent old : sourcePropnet.getComponents())
		{
			PolymorphicComponent newComp;

			if (old instanceof PolymorphicAnd)
			{
				newComp = componentFactory.createAnd(old.getInputs().size(), old.getOutputs().size());
			}
			else if (old instanceof PolymorphicOr)
			{
				newComp = componentFactory.createOr(old.getInputs().size(), old.getOutputs().size());
			}
			else if (old instanceof PolymorphicNot)
			{
				newComp = componentFactory.createNot(old.getOutputs().size());
			}
			else if (old instanceof PolymorphicProposition)
			{
				newComp = componentFactory.createProposition(old.getOutputs().size(), ((PolymorphicProposition)old).getName());
			}
			else if (old instanceof PolymorphicTransition)
			{
				newComp = componentFactory.createTransition(old.getOutputs().size());
			}
			else if (old instanceof PolymorphicConstant)
			{
				newComp = componentFactory.createConstant(old.getOutputs().size(), ((PolymorphicConstant)old).getValue());
			}
			else
			{
				throw new RuntimeException("Invalid propnet");
			}
			
			sourceToTargetMap.put(old, newComp);
			components.add(newComp);
		}
		
		//	Connect them up
		for(PolymorphicComponent old : sourcePropnet.getComponents())
		{
			PolymorphicComponent newComp = sourceToTargetMap.get(old);
			
			for(PolymorphicComponent oldInput : old.getInputs())
			{
				PolymorphicComponent newInput = sourceToTargetMap.get(oldInput);
				
				newComp.addInput(newInput);
			}
			
			for(PolymorphicComponent oldOutput : old.getOutputs())
			{
				PolymorphicComponent newOutput = sourceToTargetMap.get(oldOutput);
				
				newComp.addOutput(newOutput);
			}
		}
		
		//	Construct the various maps and collections we need to supply
		propositions = new HashSet<PolymorphicProposition>();
		for(PolymorphicProposition oldProp : sourcePropnet.getPropositions())
		{
			PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldProp);
			
			propositions.add(newProp);
		}
		basePropositions = new HashMap<GdlSentence,PolymorphicProposition>();
		for(Entry<GdlSentence, PolymorphicProposition> oldEntry : sourcePropnet.getBasePropositions().entrySet())
		{
			PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getValue());
			
			basePropositions.put(oldEntry.getKey(), newProp);
		}
		inputPropositions = new HashMap<GdlSentence,PolymorphicProposition>();
		for(Entry<GdlSentence, PolymorphicProposition> oldEntry : sourcePropnet.getInputPropositions().entrySet())
		{
			PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getValue());
			
			inputPropositions.put(oldEntry.getKey(), newProp);
		}
		legalPropositions = new HashMap<Role, PolymorphicProposition[]>();
		for(Entry<Role, PolymorphicProposition[]> oldEntry : sourcePropnet.getLegalPropositions().entrySet())
		{
			PolymorphicProposition[] newProps = new PolymorphicProposition[oldEntry.getValue().length];
			int index = 0;
			
			for(PolymorphicProposition oldProp : oldEntry.getValue())
			{
				PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldProp);
				
				newProps[index++] = newProp;
			}
			
			legalPropositions.put(oldEntry.getKey(), newProps);
		}
		goalPropositions = new HashMap<Role, PolymorphicProposition[]>();
		for(Entry<Role, PolymorphicProposition[]> oldEntry : sourcePropnet.getGoalPropositions().entrySet())
		{
			PolymorphicProposition[] newProps = new PolymorphicProposition[oldEntry.getValue().length];
			int index = 0;
			
			for(PolymorphicProposition oldProp : oldEntry.getValue())
			{
				PolymorphicProposition newProp = (PolymorphicProposition) sourceToTargetMap.get(oldProp);
				
				newProps[index++] = newProp;
			}
			
			goalPropositions.put(oldEntry.getKey(), newProps);
		}
		initProposition = (PolymorphicProposition) sourceToTargetMap.get(sourcePropnet.getInitProposition());
		terminalProposition = (PolymorphicProposition) sourceToTargetMap.get(sourcePropnet.getTerminalProposition());
		legalInputMap = new HashMap<PolymorphicProposition, PolymorphicProposition>();
		for(Entry<PolymorphicProposition, PolymorphicProposition> oldEntry : sourcePropnet.getLegalInputMap().entrySet())
		{
			PolymorphicProposition newProp1 = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getKey());
			PolymorphicProposition newProp2 = (PolymorphicProposition) sourceToTargetMap.get(oldEntry.getValue());
			
			legalInputMap.put(newProp1, newProp2);
		}
		
		roles = sourcePropnet.getRoles();
		
		basePropositionsArray = new PolymorphicProposition[basePropositions.size()];
		int index = 0;
		for(PolymorphicProposition p : sourcePropnet.getBasePropositionsArray())
		{
			basePropositionsArray[index++] = (PolymorphicProposition) sourceToTargetMap.get(p);
		}
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
		return basePropositionsArray;
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
		for ( PolymorphicComponent component : components )
		{
			sb.append("\t" + component.toString() + "\n");
		}
		sb.append("}");

		return sb.toString();
	}

	/**
     * Outputs the propnet in .dot format to a particular file.
     * This can be viewed with tools like Graphviz and ZGRViewer.
     * 
     * @param filename the name of the file to output to
     */
    public void renderToFile(String filename) {
        try {
            File f = new File(filename);
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter fout = new OutputStreamWriter(fos, "UTF-8");
            fout.write(toString());
            fout.close();
            fos.close();
        } catch(Exception e) {
            GamerLogger.logStackTrace("StateMachine", e);
        }
    }
}