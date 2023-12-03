package translation;

import minillvm.ast.*;
import notquitejava.ast.*;

import static minillvm.ast.Ast.*;

/**
 * Evaluate L values.
 */
public class ExprLValue implements NQJExprL.Matcher<Operand> {
  private final Translator tr;

  public ExprLValue(Translator translator) {
    this.tr = translator;
  }

  @Override
  public Operand case_ArrayLookup(NQJArrayLookup e) {
    Operand arrayAddr = tr.exprRvalue(e.getArrayExpr());
    tr.addNullcheck(arrayAddr, "Nullpointer exception in line " + tr.sourceLine(e));

    Operand index = tr.exprRvalue(e.getArrayIndex());

    Operand len = tr.getArrayLen(arrayAddr);
    TemporaryVar smallerZero = Ast.TemporaryVar("smallerZero");
    TemporaryVar lenMinusOne = Ast.TemporaryVar("lenMinusOne");
    TemporaryVar greaterEqualLen = Ast.TemporaryVar("greaterEqualLen");
    TemporaryVar outOfBoundsV = Ast.TemporaryVar("outOfBounds");
    final BasicBlock outOfBounds = tr.newBasicBlock("outOfBounds");
    final BasicBlock indexInRange = tr.newBasicBlock("indexInRange");


    // smallerZero = index < 0
    tr.addInstruction(BinaryOperation(smallerZero, index, Slt(), Ast.ConstInt(0)));
    // lenMinusOne = length - 1
    tr.addInstruction(BinaryOperation(lenMinusOne, len, Sub(), Ast.ConstInt(1)));
    // greaterEqualLen = lenMinusOne < index
    tr.addInstruction(BinaryOperation(greaterEqualLen,
        VarRef(lenMinusOne), Slt(), index.copy()));
    // outOfBoundsV = smallerZero || greaterEqualLen
    tr.addInstruction(BinaryOperation(outOfBoundsV,
        VarRef(smallerZero), Or(), VarRef(greaterEqualLen)));

    tr.getCurrentBlock().add(Ast.Branch(VarRef(outOfBoundsV), outOfBounds, indexInRange));

    tr.addBasicBlock(outOfBounds);
    outOfBounds.add(Ast.HaltWithError("Index out of bounds error in line " + tr.sourceLine(e)));

    tr.addBasicBlock(indexInRange);
    tr.setCurrentBlock(indexInRange);
    TemporaryVar indexAddr = Ast.TemporaryVar("indexAddr");
    tr.addInstruction(Ast.GetElementPtr(indexAddr, arrayAddr, Ast.OperandList(
        Ast.ConstInt(0),
        Ast.ConstInt(1),
        index.copy()
    )));
    return VarRef(indexAddr);
  }

  @Override
  public Operand case_FieldAccess(NQJFieldAccess e) {
    if (e.getReceiver() instanceof NQJExprThis) {
      int index = tr.getIndexOfFieldByName(tr.getCurrentClass().getName(), e.getFieldName());
      TemporaryVar fieldAddr = TemporaryVar(e.getFieldName() + "_addr");
      
      // index does not take into account vtable reference at position 0
      tr.addInstruction(GetElementPtr(fieldAddr,
          VarRef(tr.getThisParameter()), OperandList(ConstInt(0), ConstInt(index + 1))));

      return VarRef(fieldAddr);
    } else if (e.getReceiver() instanceof NQJRead) {
      NQJVarUse receiver = (NQJVarUse) ((NQJRead) e.getReceiver()).getAddress();
      NQJVarDecl declByName = tr.getDeclByName(receiver.getVarName());

      if (declByName != null) {
        String clsName = ((NQJTypeClass) declByName.getType()).getName();
        int index = tr.getIndexOfFieldByName(clsName, e.getFieldName());

        // get the LHS of field access (object struct)
        TemporaryVar var = tr.getLocalVarLocation(declByName);
        TemporaryVar load = TemporaryVar(e.getFieldName() + "_load");
        tr.addInstruction(Load(load, VarRef(var)));

        TemporaryVar fieldAddr = TemporaryVar(e.getFieldName() + "_addr");
        // index does not take into account vtable reference at position 0
        tr.addInstruction(GetElementPtr(fieldAddr, VarRef(load), OperandList(ConstInt(0), ConstInt(index + 1))));

        return VarRef(fieldAddr);
      }
    } else if (e.getReceiver() instanceof NQJNewObject) {
      NQJNewObject receiver = (NQJNewObject) e.getReceiver();
      NQJClassDecl c = tr.getClassDeclByName(receiver.getClassName());
      Operand proc = tr.getNewObjectFunc(c);
      int index = tr.getIndexOfFieldByName(c.getName(), e.getFieldName());

      TemporaryVar res = TemporaryVar("newObject");
      tr.addInstruction(Ast.Call(res, proc, OperandList()));

      TemporaryVar fieldAddr = TemporaryVar(e.getFieldName() + "_addr");
      tr.addInstruction(GetElementPtr(fieldAddr, VarRef(res), OperandList(ConstInt(0), ConstInt(index + 1))));

      return VarRef(fieldAddr);
    }

    throw new RuntimeException("Unhandled case");
  }

  @Override
  public Operand case_VarUse(NQJVarUse e) {
    NQJVarDecl varDecl = e.getVariableDeclaration();
    // local TemporaryVar
    return VarRef(tr.getLocalVarLocation(varDecl));
  }

}
