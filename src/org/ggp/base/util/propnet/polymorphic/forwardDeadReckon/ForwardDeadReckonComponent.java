package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

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

public abstract class ForwardDeadReckonComponent implements PolymorphicComponent, Serializable {
	private static final long serialVersionUID = 352527628564121134L;
    /** The inputs to the component. */
    protected ForwardDeadReckonComponent[] inputsArray = null;
    protected ForwardDeadReckonComponent singleInput = null;
    /** The outputs of the component. */
    protected ForwardDeadReckonComponent[] outputsArray = null;
    
    protected ForwardDeadReckonPropNet propNet = null;
    
    protected int inputIndex = 0;
    protected int outputIndex = 0;
    
    //protected boolean dirty;
    protected boolean cachedValue;
    protected boolean lastPropagatedValue;
    
    public static int numPropagates = 0;
    public static int numGatesPropagated = 0;
    public boolean hasQueuedForPropagation = false;

    /**
     * Creates a new Component with no inputs or outputs.
     */
    public ForwardDeadReckonComponent(int numInputs, int numOutput)
    {
    	inputsArray = new ForwardDeadReckonComponent[numInputs];
    	outputsArray = new ForwardDeadReckonComponent[numOutput];
    }

    public void setPropnet(ForwardDeadReckonPropNet propNet)
    {
    	this.propNet = propNet;
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
    		singleInput = (ForwardDeadReckonComponent)input;
    	}
    	inputsArray[inputIndex++] = (ForwardDeadReckonComponent) input;
    }

    /**
     * Adds a new output.
     * 
     * @param output
     *            A new output.
     */
    public void addOutput(PolymorphicComponent output)
    {
    	outputsArray[outputIndex++] = (ForwardDeadReckonComponent) output;
    }

    /**
     * Getter method.
     * 
     * @return The inputs to the component.  Note this should be rarely used in the finalized
     * state of the propnet
     */
    public Collection<? extends PolymorphicComponent> getInputs()
    {
    	LinkedList<ForwardDeadReckonComponent> result = new LinkedList<ForwardDeadReckonComponent>();
    	
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
    	LinkedList<ForwardDeadReckonComponent> result = new LinkedList<ForwardDeadReckonComponent>();
    	
    	for(ForwardDeadReckonComponent c : outputsArray)
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
    	return cachedValue;
    }
    
    public boolean getLastPropagatedValue()
    {
    	return lastPropagatedValue;
    }
  
	public void validate()
	{
		if ( inputIndex == 1 && !(getSingleInput() instanceof PolymorphicTransition) )
		{
			if ( cachedValue != inputsArray[0].getLastPropagatedValue())
			{
				System.out.println("Validation failure for " + toString());
			}
		}
	}

    public void queuePropagation()
    {
		//for(ForwardDeadReckonComponent output : outputsArray)
		//{
		//	output.validate();
		//}
		//propagate();
    	if ( lastPropagatedValue != cachedValue )
    	{
    		if ( !hasQueuedForPropagation && outputIndex > 0)
    		{
    			hasQueuedForPropagation = true;
    			//numGatesPropagated++;
    			
    			propNet.addToPropagateQueue(this);
    		}
    	}
    }
    
    public void propagate()
    {
 		//for(ForwardDeadReckonComponent output : outputsArray)
		//{
		//	output.validate();
		//}
		
		//System.out.println("Component " + Integer.toHexString(hashCode()) + " changing from " + lastPropagatedValue + " to " + cachedValue);
    	if ( lastPropagatedValue != cachedValue )
    	{
    		//numPropagates++;
			for(ForwardDeadReckonComponent output : outputsArray)
			{
				output.setKnownChangedState(cachedValue, this);
			}
		
			lastPropagatedValue = cachedValue;
    	}
    	
		hasQueuedForPropagation = false;
		
		//for(ForwardDeadReckonComponent output : outputsArray)
		//{
		//	output.validate();
		//}
    }
    
    public abstract void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source);
    
    public void reset()
    {
    	cachedValue = false;
    	lastPropagatedValue = false;
    	hasQueuedForPropagation = false;
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
