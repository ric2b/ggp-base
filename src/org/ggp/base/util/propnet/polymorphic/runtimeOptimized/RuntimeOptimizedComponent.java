package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

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

public abstract class RuntimeOptimizedComponent implements PolymorphicComponent, Serializable {
	private static final long serialVersionUID = 352527824700221111L;
    /** The inputs to the component. */
    protected List<RuntimeOptimizedComponent> inputs;
    protected RuntimeOptimizedComponent[] inputsArray = null;
    /** The outputs of the component. */
    protected final Set<RuntimeOptimizedComponent> outputs;
    protected RuntimeOptimizedComponent[] outputsArray = null;
    private PolymorphicComponent singleInput = null;
    
    protected boolean dirty;
    protected boolean cachedValue;
    
    public static int getCount;
    public static int dirtyCount;
    
    protected final boolean unconditionalGet = false;
    protected final boolean unconditionalDirty = false;

	public static boolean learnMode = false;

    /**
     * Creates a new Component with no inputs or outputs.
     */
    public RuntimeOptimizedComponent()
    {
        this.inputs = new LinkedList<RuntimeOptimizedComponent>();
        this.outputs = new HashSet<RuntimeOptimizedComponent>();
        
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
    		inputs.add((RuntimeOptimizedComponent) input);
    	}
    }
    
    public void removeInput(PolymorphicComponent input)
    {
    	singleInput = null;
		inputs.remove(input);
    }
    
    public void removeOutput(PolymorphicComponent output)
    {
    	outputs.remove(output);
    }
    
    public void removeAllInputs()
    {
    	singleInput = null;
		inputs.clear();
	}
    
	public void removeAllOutputs()
	{
		outputs.clear();
	}

    /**
     * Adds a new output.
     * 
     * @param output
     *            A new output.
     */
    public void addOutput(PolymorphicComponent output)
    {
        outputs.add((RuntimeOptimizedComponent) output);
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
    
    /**
     * A convenience method, to get a single input.
     * To be used only when the component is known to have
     * exactly one input.
     * 
     * @return The single input to the component.
     */
    public PolymorphicComponent getSingleInput() {
    	if ( singleInput == null )
    	{
	        assert inputs.size() == 1;
	        singleInput = inputs.iterator().next();
    	}
    	
    	return singleInput;
    }    
    
    /**
     * Getter method.
     * 
     * @return The outputs of the component.
     */
    public Set<? extends PolymorphicComponent> getOutputs()
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
    	if ( outputsArray != null )
    	{
    		return outputsArray[0];
    	}
    	else
    	{
	        assert outputs.size() == 1;
	        return outputs.iterator().next();
    	}
    }

    /**
     * Gets the value of the Component.
     * 
     * @return The value of the Component.
     */
    public  boolean getValue()
    {
    	getCount++;
    	
    	if ( dirty || unconditionalGet )
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
    
    public void setDirty(boolean from, PolymorphicComponent source)
    {
    	dirtyCount++;
    	
    	if ( unconditionalDirty || (!dirty && !unconditionalGet) )
    	{
	    	dirty = true;
	    	
	    	if ( !(this instanceof PolymorphicTransition) )
	    	{
	    		if ( outputsArray == null )
	    		{
		    		for(RuntimeOptimizedComponent output : outputs)
		    		{
		    			output.setDirty(cachedValue, this);
		    		}
	    		}
	    		else
	    		{
		    		for(RuntimeOptimizedComponent output : outputsArray)
		    		{
		    			output.setDirty(cachedValue, this);
		    		}
	    		}
	    	}
    	}
    }
    
    public void reset(boolean disable)
    {
    	if (this instanceof PolymorphicConstant)
    	{
    		dirty = true;
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
    
    public void Crystalize()
    {
    	inputsArray = new RuntimeOptimizedComponent[inputs.size()];
    	int index = 0;

    	for(RuntimeOptimizedComponent c : inputs)
    	{
    		inputsArray[index++] = c;
    	}
    	
    	outputsArray = new RuntimeOptimizedComponent[outputs.size()];
    	index = 0;

    	for(RuntimeOptimizedComponent c : outputs)
    	{
    		outputsArray[index++] = c;
    	}
    	
    	//TODO - clean up
       	//inputs = null;
       	//outputs = null;
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
