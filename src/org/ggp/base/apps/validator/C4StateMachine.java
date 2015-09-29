package org.ggp.base.apps.validator;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;

/**
 * Connect 4 specific state machine.
 */
public class C4StateMachine extends StateMachine
{
  public static long sWins = 0;

  public static final int BOARD_WIDTH = 8;
  public static final int BOARD_HEIGHT = 6;

  public Role[]         mRoles = new Role[2];
  public Move           mNoop;
  public List<Move>     mNoopList;
  public C4Move[]       mDrops = new C4Move[BOARD_WIDTH];
  public C4MachineState mState;

  @Override
  public void initialize(List<Gdl> xiDescription)
  {
    // Generate the roles.
    mRoles[0] = new Role(GdlPool.getConstant("red"));
    mRoles[1] = new Role(GdlPool.getConstant("black"));

    // Generate the no-op move.
    mNoop = new Move(GdlPool.getConstant("noop"));
    mNoopList = Arrays.asList(new Move[]{mNoop});

    // Generate the drop moves.
    for (int lii = 0; lii < BOARD_WIDTH; lii++)
    {
      mDrops[lii] = new C4Move(lii);
    }

    // Generate the initial state.
    mState = new C4MachineState();
  }

  @Override
  public Role[] getRoles()
  {
    return mRoles;
  }

  @Override
  public MachineState getInitialState()
  {
    // return mState.reset();
    return new C4MachineState();
  }

  @Override
  public boolean isTerminal(MachineState xiState)
  {
    return ((C4MachineState)xiState).mTerminal;
  }

  @Override
  public List<Move> getLegalMoves(MachineState xiState, Role xiRole)
  {
    C4MachineState lState = (C4MachineState)xiState;

    if (( lState.mRedToPlay && (xiRole == mRoles[0])) ||
        (!lState.mRedToPlay && (xiRole == mRoles[1])))
    {
      return lState.mAvailableMoves;
    }

    return mNoopList;
  }

  @Override
  public MachineState getNextState(MachineState xiState, List<Move> xiMoves)
  {
    C4MachineState lState = (C4MachineState)xiState;

    // The cell has been played in.
    int lPlayerIndex = (lState.mRedToPlay) ? 0 : 1;
    C4Move lMove = (C4Move)(xiMoves.get(lPlayerIndex));
    int lCol = lMove.mIndex;
    int lRow = lState.mFirstFreeRowInCol[lCol]++;
    lState.mCell[lCol][lRow] = lPlayerIndex;

    // Check of the column is full.
    if (lRow + 1 == C4StateMachine.BOARD_HEIGHT)
    {
      // The column is full.
      lState.mAvailableMoves.remove(lMove);
      lState.mNumOpenColumns--;

      // Check if the board is full.
      if (lState.mNumOpenColumns == 0)
      {
        // The board is full.
        lState.mTerminal = true;
      }
    }

    // Check for a win.

    // Check for a vertical win.  (Only need to look down.)
    if ((lRow >=3) &&
        (lState.mCell[lCol][lRow - 1] == lPlayerIndex) &&
        (lState.mCell[lCol][lRow - 2] == lPlayerIndex) &&
        (lState.mCell[lCol][lRow - 3] == lPlayerIndex))
    {
      lState.mTerminal = true;
      sWins++;
      return lState;
    }

    // Check for a horizontal win.  Look left and right until we meet the first counter that isn't ours.
    boolean lLookingLeft = true;
    boolean lLookingRight = true;
    int lNInARow = 1;
    for (int lii = 1; lii <= 3; lii++)
    {
      if (lLookingLeft && (lCol - lii >= 0) && (lState.mCell[lCol - lii][lRow] == lPlayerIndex))
      {
        if (++lNInARow == 4) break;
      }
      else
      {
        if (!lLookingRight) break;
        lLookingLeft = false;
      }

      if (lLookingRight &&
          (lCol + lii < C4StateMachine.BOARD_WIDTH) &&
          (lState.mCell[lCol + lii][lRow] == lPlayerIndex))
      {
        if (++lNInARow == 4) break;
      }
      else
      {
        if (!lLookingLeft) break;
        lLookingRight = false;
      }
    }

    if (lNInARow == 4)
    {
      lState.mTerminal = true;
      sWins++;
      return lState;
    }

    // Look for a primary diagonal win (top left to bottom right).
    lLookingLeft = true;
    lLookingRight = true;
    lNInARow = 1;
    for (int lii = 1; lii <= 3; lii++)
    {
      if (lLookingLeft &&
          (lCol - lii >= 0) &&
          (lRow + lii < C4StateMachine.BOARD_HEIGHT) &&
          (lState.mCell[lCol - lii][lRow + lii] == lPlayerIndex))
      {
        if (++lNInARow == 4) break;
      }
      else
      {
        if (!lLookingRight) break;
        lLookingLeft = false;
      }

      if (lLookingRight &&
          (lCol + lii < C4StateMachine.BOARD_WIDTH) &&
          (lRow - lii >= 0) &&
          (lState.mCell[lCol + lii][lRow - lii] == lPlayerIndex))
      {
        if (++lNInARow == 4) break;
      }
      else
      {
        if (!lLookingLeft) break;
        lLookingRight = false;
      }
    }

    if (lNInARow == 4)
    {
      lState.mTerminal = true;
      sWins++;
      return lState;
    }

    // Look for a secondary diagonal win (bottom left to top right).
    lLookingLeft = true;
    lLookingRight = true;
    lNInARow = 1;
    for (int lii = 1; lii <= 3; lii++)
    {
      if (lLookingLeft &&
          (lCol - lii >= 0) &&
          (lRow - lii >= 0) &&
          (lState.mCell[lCol - lii][lRow - lii] == lPlayerIndex))
      {
        if (++lNInARow == 4) break;
      }
      else
      {
        if (!lLookingRight) break;
        lLookingLeft = false;
      }

      if (lLookingRight &&
          (lCol + lii < C4StateMachine.BOARD_WIDTH) &&
          (lRow + lii < C4StateMachine.BOARD_HEIGHT) &&
          (lState.mCell[lCol + lii][lRow + lii] == lPlayerIndex))
      {
        if (++lNInARow == 4) break;
      }
      else
      {
        if (!lLookingLeft) break;
        lLookingRight = false;
      }
    }

    if (lNInARow == 4)
    {
      lState.mTerminal = true;
      sWins++;
      return lState;
    }

    // It's the other player's turn
    lState.mRedToPlay = !lState.mRedToPlay;

    return lState;
  }

  @Override
  public int getGoal(MachineState xiState, Role xiRole)
  {
    return 50;
  }

  public class C4MachineState extends MachineState
  {
    public boolean mRedToPlay;
    public boolean mTerminal;
    public final int[] mFirstFreeRowInCol = new int[C4StateMachine.BOARD_WIDTH];
    public final List<Move> mAvailableMoves = new LinkedList<>();
    public int mNumOpenColumns;

    // Indexed by col,row.
    public final int[][] mCell = new int[C4StateMachine.BOARD_WIDTH][C4StateMachine.BOARD_HEIGHT];

    public C4MachineState()
    {
      reset();
    }

    public C4MachineState reset()
    {
      mRedToPlay = true;
      mTerminal = false;
      mNumOpenColumns = C4StateMachine.BOARD_WIDTH;
      mAvailableMoves.clear();

      for (int lii = 0; lii < C4StateMachine.BOARD_WIDTH; lii++)
      {
        mFirstFreeRowInCol[lii] = 0;
        mAvailableMoves.add(mDrops[lii]);

        for (int ljj = 0; ljj < C4StateMachine.BOARD_HEIGHT; ljj++)
        {
          mCell[lii][ljj] = -1;
        }
      }

      return this;
    }

    public void dump()
    {
      StringBuffer lBoard = new StringBuffer(C4StateMachine.BOARD_HEIGHT * C4StateMachine.BOARD_WIDTH * 3);
      for (int lRow = C4StateMachine.BOARD_HEIGHT - 1; lRow >= 0; lRow--)
      {
        for (int lCol = 0; lCol < C4StateMachine.BOARD_WIDTH; lCol++)
        {
          lBoard.append(mCell[lCol][lRow] + 1);
          lBoard.append(' ');
        }
        lBoard.append('\n');
      }
      lBoard.append("----------------\n");

      if (mRedToPlay)
      {
        System.err.println(lBoard.toString());
      }
      else
      {
        System.out.println(lBoard.toString());
      }
    }
  }

  public static class C4Move extends Move
  {
    private static final long serialVersionUID = 1L;

    public final int mIndex;

    public C4Move(int xiIndex)
    {
      super(GdlPool.getFunction(GdlPool.getConstant("drop"),
            new GdlTerm[] {GdlPool.getConstant("" + (xiIndex + 1))}));
      mIndex = xiIndex;
    }
  }
}