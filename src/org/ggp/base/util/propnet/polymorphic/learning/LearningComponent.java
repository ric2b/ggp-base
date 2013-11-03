package org.ggp.base.util.propnet.polymorphic.learning;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

public abstract class LearningComponent extends BidirectionalPropagationComponent implements Serializable {
	private static final long serialVersionUID = 352527824700221111L;
    /** The inputs to the component. */
    protected List<LearningComponent> inputs;
    /** The outputs of the component. */
    protected final Set<LearningComponent> outputs;
    
    protected boolean dirty;
    protected boolean cachedValue;
    
    public static int getCount;
    public static int dirtyCount;
 
    /**
     * Creates a new Component with no inputs or outputs.
     */
    public LearningComponent()
    {
        this.inputs = new LinkedList<LearningComponent>();
        this.outputs = new HashSet<LearningComponent>();
        
        dirty = true;
    }

    /**
     * Adds a new input.
     * 
     * @param input
     *            A new input.
     */
    public void addInput(PolymorphicComponent input)
    {
    	if ( !inputs.contains(input))
    	{
    		inputs.add((LearningComponent) input);
    	}
    }
 
    /**
     * Adds a new output.
     * 
     * @param output
     *            A new output.
     */
    public void addOutput(PolymorphicComponent output)
    {
        outputs.add((LearningComponent) output);
    }

    /**
     * Getter method.
     * 
     * @return The inputs to the component.
     */
    public Collection<? extends PolymorphicComponent> getInputs()
    {
        return inputs;
    }

	public void removeInput(PolymorphicComponent input)
	{
		inputs.remove(input);
	}

	public void removeAllInputs()
	{
		inputs.clear();
	}

	public void removeAllOutputs()
	{
		outputs.clear();
	}

	public void removeOutput(PolymorphicComponent output)
	{
		outputs.remove(output);
	}
	
	public void crystalize()
	{
	}
    
    /**
     * A convenience method, to get a single input.
     * To be used only when the component is known to have
     * exactly one input.
     * 
     * @return The single input to the component.
     */
    public PolymorphicComponent getSingleInput() {
        assert inputs.size() == 1;
        return inputs.iterator().next();
    }    
    
    /**
     * Getter method.
     * 
     * @return The outputs of the component.
     */
    public Collection<? extends PolymorphicComponent> getOutputs()
    {
        return outputs;
    }
    
    /**
     * A convenience method, to get a single output.
     * To be used only when the component is known to have
     * exactly one output.
     * 
     * @return The single output to the component.
     */
    public PolymorphicComponent getSingleOutput() {
        assert outputs.size() == 1;
        return outputs.iterator().next();
    }

    /**
     * Gets the value of the Component.
     * 
     * @return The value of the Component.
     */
    public  boolean getValue()
    {
    	getCount++;
    	
    	if ( dirty )
    	{
    		dirty = false;
    		cachedValue = getValueInternal();
     	}
    	
    	return cachedValue;
    }
    
    public boolean isDirty()
    {
    	return dirty;
    }
    
    public void setDirty(boolean from, BidirectionalPropagationComponent source)
    {
    	dirtyCount++;
    	
    	if ( !dirty  )
    	{
	    	dirty = true;
	    	
	    	if ( !(this instanceof PolymorphicTransition) )
	    	{
	    		for(LearningComponent output : outputs)
	    		{
	    			output.setDirty(cachedValue, this);
	    		}
	    	}
    	}
    }
    
    public void reset(boolean disable)
    {
    	if (this instanceof PolymorphicConstant)
    	{
    		dirty = false;
    		cachedValue = getValueInternal();
    	}
    	else
    	{
    		if (disable)
    		{
    			dirty = false;
    			cachedValue = false;
    		}
    		else
    		{
    			dirty = true;
			}
    	}
    }

    protected class EncapsulatedCost
    {
    	private int cost = 0;
    	
    	public int getCost()
    	{
    		return cost;
    	}
    	
    	public void incrementCost()
    	{
    		cost++;
    	}
    }
    
    protected abstract boolean getValueAndCost(EncapsulatedCost aggregatedCost);
    
    /**
     * Calculates the value of the Component.
     * 
     * @return The value of the Component.
     */
    protected abstract boolean getValueInternal();

    public void Optimize()
    {
    }
    
    /**
     * Returns a configurable representation of the Component in .dot format.
     * 
     * @param shape
     *            The value to use as the <tt>shape</tt> attribute.
     * @param fillcolor
     *            The value to use as the <tt>fillcolor</tt> attribute.
     * @param label
     *            The value to use as the <tt>label</tt> attribute.
     * @return A representation of the Component in .dot format.
     */
    protected String toDot(String shape, String fillcolor, String label)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("\"@" + Integer.toHexString(hashCode()) + "\"[shape=" + shape + ", style= filled, fillcolor=" + fillcolor + ", label=\"" + label + "\"]; ");
        for ( PolymorphicComponent component : getOutputs() )
        {
            sb.append("\"@" + Integer.toHexString(hashCode()) + "\"->" + "\"@" + Integer.toHexString(component.hashCode()) + "\"; ");
        }

        return sb.toString();
    }
}
