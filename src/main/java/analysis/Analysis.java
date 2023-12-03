package analysis;

import notquitejava.ast.*;

import java.util.*;


/**
 * Analysis visitor to handle most of the type rules specified to NQJ.
 */
public class Analysis extends NQJElement.DefaultVisitor {

  private final NQJProgram prog;
  private final List<TypeError> typeErrors = new ArrayList<>();
  private final LinkedList<TypeContext> ctxt = new LinkedList<>();
  private final LinkedList<ClassContext> clsCtxtList = new LinkedList<>();
  private final Map<String, ClassContext> clsCtxtMap = new HashMap<>();
  private NameTable nameTable;

  public Analysis(NQJProgram prog) {
    this.prog = prog;
  }

  public void addError(NQJElement element, String message) {
    typeErrors.add(new TypeError(element, message));
  }

  /**
   * Checks the saves NQJProgram for type errors.
   * Main entry point for type checking.
   */
  public void check() {
    nameTable = new NameTable(this, prog);

    createClassContexts();
    verifyMainMethod();

    prog.accept(this);
  }

  private void createClassContexts() {
    // initialize class contexts for the first time
    for (NQJClassDecl c : prog.getClassDecls()) {
      ClassContext context = new ClassContext(c);

      for (NQJVarDecl v : c.getFields()) {
        context.putField(v.getName(), v);
      }

      for (NQJFunctionDecl m : c.getMethods()) {
        context.putMethod(m.getName(), m);
      }

      clsCtxtMap.put(c.getName(), context);
    }

    // update context with methods and fields from superclass
    for (NQJClassDecl c : prog.getClassDecls()) {
      List<String> ancestorNames = nameTable.getAncestorNames(c.getName());
      ClassContext curCtxt = clsCtxtMap.get(c.getName());

      for (String name : ancestorNames) {
        for (NQJVarDecl v : getClassDeclByName(name).getFields()) {
          curCtxt.putField(v.getName(), v);
        }

        for (NQJFunctionDecl m : getClassDeclByName(name).getMethods()) {
          curCtxt.putMethod(m.getName(), m);
        }
      }

      clsCtxtMap.put(c.getName(), curCtxt);
    }

  }


  private void verifyMainMethod() {
    var main = nameTable.lookupFunction("main");
    if (main == null) {
      typeErrors.add(new TypeError(prog, "Method int main() must be present"));
      return;
    }
    if (!(main.getReturnType() instanceof NQJTypeInt)) {
      typeErrors.add(new TypeError(main.getReturnType(),
          "Return type of the main method must be int"));
    }
    if (!(main.getFormalParameters().isEmpty())) {
      typeErrors.add(new TypeError(main.getFormalParameters(),
          "Main method does not take parameters"));
    }
    // Check if return statement is there as the last statement
    NQJStatement last = null;
    for (NQJStatement nqjStatement : main.getMethodBody()) {
      last = nqjStatement;
    }
    // TODO this forces the main method to have a single return at the end
    //      instead check for all possible paths to end in a return statement
    if (!(last instanceof NQJStmtReturn)) {
      typeErrors.add(new TypeError(main.getFormalParameters(),
          "Main method does not have a return statement as the last statement"));
    }
  }

  @Override
  public void visit(NQJFunctionDecl m) {
    // parameter names are unique, build context
    TypeContext mctxt = this.ctxt.isEmpty()
        ? new TypeContextImpl(null, Type.INVALID)
        : this.ctxt.peek().copy();
    Set<String> paramNames = new HashSet<>();
    for (NQJVarDecl v : m.getFormalParameters()) {
      if (!paramNames.add(v.getName())) {
        addError(m, "Parameter with name " + v.getName() + " already exists.");
      }
      mctxt.putVar(v.getName(), type(v.getType()), v);
    }
    mctxt.setReturnType(type(m.getReturnType()));
    // enter method context
    ctxt.push(mctxt);

    m.getMethodBody().accept(this);

    // exit method context
    ctxt.pop();
  }

  @Override
  public void visit(NQJClassDecl classDecl) {
    Set<String> fieldNames = new HashSet<>();
    Set<String> methodNames = new HashSet<>();
    // add current class context to a list
    clsCtxtList.push(clsCtxtMap.get(classDecl.getName()));

    // check the name uniqueness for fields
    for (NQJVarDecl f : classDecl.getFields()) {
      if (!fieldNames.add(f.getName())) {
        addError(classDecl, "Field with name " + f.getName() + " already exists.");
      }
    }

    // prevent overloading from superclass methods
    for (String name : nameTable.getAncestorNames(classDecl.getName())) {
      ClassContext ancestorCtxt = clsCtxtMap.get(name);

      for (NQJFunctionDecl m : classDecl.getMethods()) {
        NQJFunctionDecl methodFromAncestor =
            ancestorCtxt.lookupMethod(m.getName());

        if (methodFromAncestor != null) {
          Type ancestorReturnType = type(methodFromAncestor.getReturnType());
          Type returnType = type(m.getReturnType());

          // if types are equal, it is overriding (which is allowed in NQJ)
          // if the size of arguments is not equal, it is overloading
          if (ancestorReturnType.isEqualToType(returnType)
              && methodFromAncestor.getFormalParameters().size() == m.getFormalParameters().size()) {
            for (int i = 0; i < m.getFormalParameters().size(); i++) {
              Type type = type(m.getFormalParameters().get(i).getType());
              Type ancestorType = type(methodFromAncestor.getFormalParameters().get(i).getType());
              if (!type.equals(ancestorType)) {
                addError(m,
                    "Arguments must have the same type");
              }
            }
          } else {
            addError(m,
                "Overloading of " + m.getName() + " is illegal.");
          }
        }
      }
    }

    // check the name uniqueness for methods
    for (NQJFunctionDecl m : classDecl.getMethods()) {
      if (!methodNames.add(m.getName())) {
        addError(classDecl,
            "Method with name " + m.getName() + " already exists.");
      }

      // visit a method
      m.accept(this);
    }
  }


  @Override
  public void visit(NQJStmtReturn stmtReturn) {
    Type actualReturn = checkExpr(ctxt.peek(), stmtReturn.getResult());
    Type expectedReturn = ctxt.peek().getReturnType();
    if (!actualReturn.isSubtypeOf(expectedReturn)) {
      addError(stmtReturn, "Should return value of type " + expectedReturn
          + ", but found " + actualReturn + ".");
    }
  }

  @Override
  public void visit(NQJStmtAssign stmtAssign) {
    Type lt = checkExpr(ctxt.peek(), stmtAssign.getAddress());
    Type rt = checkExpr(ctxt.peek(), stmtAssign.getValue());
    if (!rt.isSubtypeOf(lt)) {
      addError(stmtAssign.getValue(), "Cannot assign value of type " + rt
          + " to " + lt + ".");
    }
  }

  @Override
  public void visit(NQJStmtExpr stmtExpr) {
    checkExpr(ctxt.peek(), stmtExpr.getExpr());
  }

  @Override
  public void visit(NQJStmtWhile stmtWhile) {
    Type ct = checkExpr(ctxt.peek(), stmtWhile.getCondition());
    if (!ct.isSubtypeOf(Type.BOOL)) {
      addError(stmtWhile.getCondition(),
          "Condition of while-statement must be of type boolean, but this is of type "
              + ct + ".");
    }
    super.visit(stmtWhile);
  }

  @Override
  public void visit(NQJStmtIf stmtIf) {
    Type ct = checkExpr(ctxt.peek(), stmtIf.getCondition());
    if (!ct.isSubtypeOf(Type.BOOL)) {
      addError(stmtIf.getCondition(),
          "Condition of if-statement must be of type boolean, but this is of type "
              + ct + ".");
    }
    super.visit(stmtIf);
  }


  @Override
  public void visit(NQJBlock block) {
    TypeContext bctxt = this.ctxt.peek().copy();

    for (NQJStatement s : block) {
      // could also be integrated into the visitor run
      if (s instanceof NQJVarDecl) {
        NQJVarDecl varDecl = (NQJVarDecl) s;

        // we set classDecl and type for varDecl because it will be important subsequently
        if (varDecl.getType() instanceof NQJTypeClass) {
          NQJTypeClass typeClass = (NQJTypeClass) varDecl.getType();
          typeClass.setClassDeclaration(getClassDeclByName(typeClass.getName()));
          varDecl.setType(typeClass);
        }

        TypeContextImpl.VarRef ref = bctxt.lookupVar(varDecl.getName());
        if (ref != null) {
          addError(varDecl, "A variable with name " + varDecl.getName()
              + " is already defined.");
        }
        bctxt.putVar(varDecl.getName(), type(varDecl.getType()), varDecl);
      } else {
        // enter block context
        ctxt.push(bctxt);
        s.accept(this);
        // exit block context
        ctxt.pop();
      }
    }
  }


  @Override
  public void visit(NQJVarDecl varDecl) {
    throw new RuntimeException(); // var decls already handled by NQJBlock and NQJFunctionDecl
  }

  public Type checkExpr(TypeContext ctxt, NQJExpr e) {
    return e.match(new ExprChecker(this, ctxt, clsCtxtList.peek()));
  }

  public Type checkExpr(TypeContext ctxt, NQJExprL e) {
    return e.match(new ExprChecker(this, ctxt, clsCtxtList.peek()));
  }

  /**
   * NQJ AST element to Type converter.
   */
  public Type type(NQJType type) {
    Type result = type.match(new NQJType.Matcher<>() {

      @Override
      public Type case_TypeBool(NQJTypeBool typeBool) {
        return Type.BOOL;
      }

      @Override
      public Type case_TypeClass(NQJTypeClass typeClass) {
        ClassContext context = clsCtxtMap.get(typeClass.getName());

        if (context == null) {
          typeErrors.add(new TypeError(typeClass,
              "ClassContext for class " + typeClass.getName() + " is not defined"));
          return Type.ANY;
        }

        return new TypeClass(typeClass.getName(), nameTable);
      }

      @Override
      public Type case_TypeArray(NQJTypeArray typeArray) {
        return nameTable.getArrayType(type(typeArray.getComponentType()));
      }

      @Override
      public Type case_TypeInt(NQJTypeInt typeInt) {
        return Type.INT;
      }

    });

    type.setType(result);
    return result;
  }

  NQJClassDecl getClassDeclByName(String name) {
    for (NQJClassDecl classDecl : prog.getClassDecls()) {
      if (classDecl.getName().equals(name)) {
        return classDecl;
      }
    }

    throw new RuntimeException("Class " + name + " is not defined");
  }


  public NameTable getNameTable() {
    return nameTable;
  }

  public List<TypeError> getTypeErrors() {
    return new ArrayList<>(typeErrors);
  }

  public ClassContext lookupContext(String c) {
    return clsCtxtMap.get(c);
  }
}
