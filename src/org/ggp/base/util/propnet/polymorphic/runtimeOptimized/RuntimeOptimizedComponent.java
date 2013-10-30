package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

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

public abstract class RuntimeOptimizedComponent extends BidirectionalPropagationComponent implements Serializable {
	private static final long serialVersionUID = 352527628564121134L;
    /** The inputs to the component. */
    protected RuntimeOptimizedComponent[] inputsArray = null;
    protected RuntimeOptimizedComponent singleInput = null;
    /** The outputs of the component. */
    protected RuntimeOptimizedComponent[] outputsArray = null;
    
    protected int inputIndex = 0;
    private int outputIndex = 0;
    
    protected boolean dirty;
    protected boolean cachedValue;

    /**
     * Creates a new Component with no inputs or outputs.
     */
    public RuntimeOptimizedComponent(int numInputs, int numOutput)
    {
    	inputsArray = new RuntimeOptimizedComponent[numInputs];
    	outputsArray = new RuntimeOptimizedComponent[numOutput];
        
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
    	if ( inputIndex == 0 )
    	{
    		singleInput = (RuntimeOptimizedComponent)input;
    	}
    	inputsArray[inputIndex++] = (RuntimeOptimizedComponent) input;
    }

    /**
     * Adds a new output.
     * 
     * @param output
     *            A new output.
     */
    public void addOutput(PolymorphicComponent output)
    {
    	outputsArray[outputIndex++] = (RuntimeOptimizedComponent) output;
    }

    /**
     * Getter method.
     * 
     * @return The inputs to the component.  Note this should be rarely used in the finalized
     * state of the propnet
     */
    public Collection<? extends PolymorphicComponent> getInputs()
    {
    	LinkedList<RuntimeOptimizedComponent> result = new LinkedList<RuntimeOptimizedComponent>();
    	
    	for(int i = 0; i < inputIndex; i++)
    	{
    		result.add(inputsArray[i]);
    	}
    	
        return result;
    }
    
    /**
     * A convenience method, to get a single input.
     * To be used only when the component is known to have
     * exactly one input.
     * 
     * @return The single input to the component.
     */
    public PolymorphicComponent getSingleInput() {
     	return singleInput;
    }    
    
    /**
     * Getter method.
     * 
     * @return The outputs of the component.
     */
    public Collection<? extends PolymorphicComponent> getOutputs()
    {
    	LinkedList<RuntimeOptimizedComponent> result = new LinkedList<RuntimeOptimizedComponent>();
    	
    	for(RuntimeOptimizedComponent c : outputsArray)
    	{
    		result.add(c);
    	}
    	
        return result;
   }
    
    /**
     * A convenience method, to get a single output.
     * To be used only when the component is known to have
     * exactly one output.
     * 
     * @return The single output to the component.
     */
    public PolymorphicComponent getSingleOutput() {
    	return outputsArray[0];
    }

    /**
     * Gets the value of the Component.
     * 
     * @return The value of the Component.
     */
    public  boolean getValue()
    {
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
    	if ( !dirty )
    	{
	    	dirty = true;
	    	
    		for(RuntimeOptimizedComponent output : outputsArray)
    		{
    			output.setDirty(cachedValue, this);
    		}
    	}
    }
    
    public void setKnownChangedState(boolean newState, BidirectionalPropagationComponent source)
    {
    	if ( !dirty )
    	{
	    	dirty = true;
	    	
    		for(RuntimeOptimizedComponent output : outputsArray)
    		{
    			output.setDirty(cachedValue, this);
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
    
    /**
     * Calculates the value of the Component.
     * 
     * @return The value of the Component.
     */
    protected abstract boolean getValueInternal();
    
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
