package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.List;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class ForwardDeadReckonPropnetFastAnimator
{
  private ForwardDeadReckonPropNet propNet;
  private long[]                   componentInfo;
  //  each long is a packed record using the following masks:
  private final long               componentInfoTypeMask = 0x07; //  3-bits for component type
  private final int                componentInfoTypeShift = 0;
  private final long               componentInfoOutputCountMask = 0xFFFFFFF8L; // Num outputs mask
  private final int                componentInfoOutputCountShift = 3;
  private final long               componentInfoConnectivityOffsetMask = 0xFFFFFFFF00000000L; // Start offset of output indexes in connectivity array
  private final int                componentInfoConnectivityOffsetShift = 32;

  private ForwardDeadReckonComponent[] components;
  private ForwardDeadReckonLegalMoveInfo[] componentAssociatedLegalMoves;
  private int[]                    componentConnectivityTable;
  //  Component types
  private final int                componentTypeProposition = 0;
  private final int                componentTypeTransition = 1;
  private final int                componentTypeOr = 2;
  private final int                componentTypeAnd = 3;
  private final int                componentTypeNot = 4;
  private final int                componentTypeTrueConstant = 5;
  private final int                componentTypeFalseConstant = 6;

  // states will be represented by per-thread int arrays, with each int packed as follows
  private final int                componentInfoOpaqueValueMask = 0x3FFFFFFF; // value mask
  private final int                componentInfoCachedValMask = 1<<31; // Current state
  private final int                componentInfoLastPropagatedMask = 1<<30; // Last propagated state

  private int[][]                  stateVectors = null;

  private int[]                    resetWatermark = null;

  public ForwardDeadReckonPropnetFastAnimator(ForwardDeadReckonPropNet propNet)
  {
    this.propNet = propNet;

    int index = 0;
    int outputCount = 0;

    for(PolymorphicComponent c : propNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      outputCount += fdrc.getOutputs().size();
      fdrc.id = index++;
    }

    componentInfo = new long[index];
    components = new ForwardDeadReckonComponent[index];
    componentAssociatedLegalMoves = new ForwardDeadReckonLegalMoveInfo[index];
    componentConnectivityTable = new int[outputCount];
    int componentOffset = 0;

    for(PolymorphicComponent c : propNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;
      List<PolymorphicComponent> outputs = (List<PolymorphicComponent>)c.getOutputs();
      int componentType = 0;

      if ( c instanceof PolymorphicProposition )
      {
        componentType = componentTypeProposition;
        componentAssociatedLegalMoves[fdrc.id] = ((ForwardDeadReckonProposition)c).getAssociatedMove();
      }
      else if ( c instanceof PolymorphicTransition )
      {
        componentType = componentTypeTransition;
      }
      else if ( c instanceof PolymorphicOr )
      {
        componentType = componentTypeOr;
      }
      else if ( c instanceof PolymorphicAnd )
      {
        componentType = componentTypeAnd;
      }
      else if ( c instanceof PolymorphicNot )
      {
        componentType = componentTypeNot;
      }
      else if ( c instanceof PolymorphicConstant )
      {
        if ( c.getValue() )
        {
          componentType = componentTypeTrueConstant;
        }
        else
        {
          componentType = componentTypeFalseConstant;
        }
      }
      components[fdrc.id]= fdrc;
      componentInfo[fdrc.id] = ((long)componentOffset << componentInfoConnectivityOffsetShift) |
                               ((long)outputs.size() << componentInfoOutputCountShift) |
                               ((long)componentType << componentInfoTypeShift);
      for(PolymorphicComponent output : outputs)
      {
        componentConnectivityTable[componentOffset++] = ((ForwardDeadReckonComponent)output).id;
      }
    }
  }

  public void crystalize(int numInstances)
  {
    stateVectors = new int[numInstances][];
    resetWatermark = new int[numInstances];

    for(int i = 0; i < numInstances; i++)
    {
      stateVectors[i] = new int[propNet.getComponents().size()];
    }
  }

  public void reset(int instanceId, boolean fullPropagate)
  {
    int[] state = stateVectors[instanceId];
    ForwardDeadReckonLegalMoveSet moveSet = propNet.getActiveLegalProps(instanceId);

    for(int i = 0; i < componentInfo.length; i++)
    {
      long compInfo = componentInfo[i];
      int componentType = (int)((compInfo & componentInfoTypeMask) >> componentInfoTypeShift);

      switch(componentType)
      {
        case componentTypeProposition:
        case componentTypeTransition:
        case componentTypeOr:
        case componentTypeFalseConstant:
          state[i] = 0;
          break;
        case componentTypeAnd:
          state[i] = components[i].inputsArray.length;
          break;
        case componentTypeNot:
          state[i] = componentInfoCachedValMask;
          break;
        case componentTypeTrueConstant:
          state[i] = componentInfoCachedValMask;
          break;
        default:
          break;
      }
    }

    if ( fullPropagate )
    {
      for(int i = 0; i < componentInfo.length; i++)
      {
        //boolean lastPropagated = ((state[i] & componentInfoLastPropagatedMask) != 0);
        boolean current = ((state[i] & componentInfoCachedValMask) != 0);
        int type = (int)((componentInfo[i] & componentInfoTypeMask) >> componentInfoTypeShift);

        resetWatermark[instanceId] = i;

        if ( current && type != componentTypeTransition )
        {
          propagateComponent(moveSet, instanceId, state, i, current);
        }
      }
    }
    else
    {
      resetWatermark[instanceId] = componentInfo.length;
    }
  }

  private void propagateComponent(ForwardDeadReckonLegalMoveSet moveSet, int instanceId, int[] state, int componentId, boolean value)
  {
    if ( resetWatermark[instanceId] >= componentId )
    {
      long  compInfo = componentInfo[componentId];
      int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
      int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);

      //System.out.println("Nwe value " + value + " for component " + components[componentId]);
//      if ( value )
//      {
//        state[componentId] |= componentInfoLastPropagatedMask;
//      }
//      else
//      {
//        state[componentId] &= ~componentInfoLastPropagatedMask;
//      }

      if ( value )
      {
        while(numOutputs-- > 0)
        {
          int outputId = componentConnectivityTable[outputIndex++];
          long outputInfo = componentInfo[outputId];
          int outputType = (int)((outputInfo & componentInfoTypeMask) >> componentInfoTypeShift);
          int stateVal;

          switch(outputType)
          {
            case componentTypeProposition:
              state[outputId] |= componentInfoCachedValMask;

              ForwardDeadReckonLegalMoveInfo moveInfo = componentAssociatedLegalMoves[outputId];
              if ( moveInfo != null )
              {
                moveSet.add(moveInfo);
              }
              //components[outputId].noteNewValue(instanceId, true);
              if ( (outputInfo & componentInfoOutputCountMask) != 0 )
              {
                propagateComponent(moveSet, instanceId, state, outputId, true);
              }
              break;
            case componentTypeTransition:
              state[outputId] |= componentInfoCachedValMask;
              components[outputId].noteNewValue(instanceId, true);
              break;
            case componentTypeOr:
              stateVal = state[outputId] + 1;

              if ((stateVal & componentInfoOpaqueValueMask) == 1)
              {
                state[outputId] = stateVal | componentInfoCachedValMask;
                propagateComponent(moveSet, instanceId, state, outputId, true);
              }
              else
              {
                state[outputId] = stateVal;
              }
              break;
            case componentTypeAnd:
              stateVal = state[outputId] - 1;

              if ((stateVal & componentInfoOpaqueValueMask) == 0)
              {
                state[outputId] = stateVal | componentInfoCachedValMask;
                propagateComponent(moveSet, instanceId, state, outputId, true);
              }
              else
              {
                state[outputId] = stateVal;
              }
              break;
            case componentTypeNot:
              state[outputId] &= ~componentInfoCachedValMask;
              propagateComponent(moveSet, instanceId, state, outputId, false);
              break;
            default:
              //  Should not happen
              throw new UnsupportedOperationException("Unexpected component type");
          }
        }
      }
      else
      {
        while(numOutputs-- > 0)
        {
          int outputId = componentConnectivityTable[outputIndex++];
          long outputInfo = componentInfo[outputId];
          int outputType = (int)((outputInfo & componentInfoTypeMask) >> componentInfoTypeShift);
          int stateVal;

          switch(outputType)
          {
            case componentTypeProposition:
              state[outputId] &= ~componentInfoCachedValMask;
              ForwardDeadReckonLegalMoveInfo moveInfo = componentAssociatedLegalMoves[outputId];
              if ( moveInfo != null )
              {
                moveSet.remove(moveInfo);
              }
              //components[outputId].noteNewValue(instanceId, false);
              if ( (outputInfo & componentInfoOutputCountMask) != 0 )
              {
                propagateComponent(moveSet, instanceId, state, outputId, false);
              }
              break;
            case componentTypeTransition:
              state[outputId] &= ~componentInfoCachedValMask;
              components[outputId].noteNewValue(instanceId, false);
              break;
            case componentTypeOr:
              stateVal = state[outputId] - 1;

              if ((stateVal & componentInfoOpaqueValueMask) == 0)
              {
                state[outputId] = stateVal & ~componentInfoCachedValMask;
                propagateComponent(moveSet, instanceId, state, outputId, false);
              }
              else
              {
                state[outputId] = stateVal;
              }
              break;
            case componentTypeAnd:
              stateVal = state[outputId] + 1;

              if ((stateVal & componentInfoOpaqueValueMask) == 1)
              {
                state[outputId] = stateVal & ~componentInfoCachedValMask;
                propagateComponent(moveSet, instanceId, state, outputId, false);
              }
              else
              {
                state[outputId] = stateVal;
              }
              break;
            case componentTypeNot:
              state[outputId] |= componentInfoCachedValMask;
              propagateComponent(moveSet, instanceId, state, outputId, true);
              break;
            default:
              //  Should not happen
              throw new UnsupportedOperationException("Unexpected component type");
          }
        }
      }
    }
  }

  public void setComponentValue(int instanceId, int propId, boolean value)
  {
    int[] state = stateVectors[instanceId];
    boolean currentValue = ((state[propId] & componentInfoCachedValMask) != 0);

    if ( value != currentValue )
    {
      if ( value )
      {
        state[propId] |= componentInfoCachedValMask;
      }
      else
      {
        state[propId] &= ~componentInfoCachedValMask;
      }

      propagateComponent(propNet.getActiveLegalProps(instanceId), instanceId, state, propId, value);
    }
  }

  public boolean getComponentValue(int instanceId, int propId)
  {
    int[] state = stateVectors[instanceId];
    return ((state[propId] & componentInfoCachedValMask) != 0);
  }
}
