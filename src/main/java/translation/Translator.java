package translation;

import minillvm.ast.BasicBlock;
import minillvm.ast.Global;
import minillvm.ast.Parameter;
import minillvm.ast.ParameterList;
import minillvm.ast.Proc;
import minillvm.ast.Prog;
import minillvm.ast.StructField;
import minillvm.ast.TemporaryVar;
import minillvm.ast.TypeArray;
import minillvm.ast.TypeBool;
import minillvm.ast.TypeByte;
import minillvm.ast.TypeInt;
import minillvm.ast.TypeNullpointer;
import minillvm.ast.TypePointer;
import minillvm.ast.TypeProc;
import minillvm.ast.TypeStruct;
import minillvm.ast.TypeVoid;
import minillvm.ast.*;
import notquitejava.ast.*;

import java.util.*;
import java.util.stream.Collectors;

import static frontend.AstPrinter.print;
import static minillvm.ast.Ast.*;


/**
 * Entry class for the translation phase.
 */
public class Translator {

  private final StmtTranslator stmtTranslator = new StmtTranslator(this);
  private final ExprLValue exprLValue = new ExprLValue(this);
  private final ExprRValue exprRValue = new ExprRValue(this);
  private final Map<NQJFunctionDecl, Proc> functionImpl = new HashMap<>();
  private final Prog prog = Prog(TypeStructList(), GlobalList(), ProcList());
  private final NQJProgram javaProg;
  private final Map<NQJVarDecl, TemporaryVar> localVarLocation = new HashMap<>();
  private final Map<NQJType, Type> translatedType = new HashMap<>();
  private final Map<Type, TypeStruct> arrayStruct = new HashMap<>();
  private final Map<Type, Proc> newArrayFuncForType = new HashMap<>();

  private final Map<String, NQJVarDecl> nameVarDecl = new HashMap<>();
  private final Map<NQJClassDecl, TypeStruct> virtualTableStruct = new HashMap<>();
  private final Map<NQJClassDecl, Global> virtualTableGlobal = new HashMap<>();
  private final Map<NQJClassDecl, TypeStruct> objectStruct = new HashMap<>();
  private final Map<NQJClassDecl, Proc> newObjectFuncForType = new HashMap<>();

  // mutable state
  private Proc currentProcedure;
  private BasicBlock currentBlock;
  private NQJClassDecl currentClass;

  public Translator(NQJProgram javaProg) {
    this.javaProg = javaProg;
  }

  /**
   * Translates given program into a mini llvm program.
   */
  public Prog translate() {

    // in the beginning empty structs are created
    createVirtualTables();
    createObjectStructs();

    // translate functions except main
    // has only access to functions
    translateFunctions();

    // translation of classes is similar to global function translation
    translateClasses();

    // translate main function
    // has access to functions
    translateMainFunction();

    finishNewObjectProcs();
    finishNewArrayProcs();

    return prog;
  }

  TemporaryVar getLocalVarLocation(NQJVarDecl varDecl) {
    return localVarLocation.get(varDecl);
  }

  NQJVarDecl getDeclByName(String name) {
    return nameVarDecl.get(name);
  }

  private void finishNewArrayProcs() {
    for (Type type : newArrayFuncForType.keySet()) {
      finishNewArrayProc(type);
    }
  }

  private void finishNewObjectProcs() {
    for (NQJClassDecl c : newObjectFuncForType.keySet()) {
      finishNewObjectProc(c);
    }
  }

  private void finishNewObjectProc(NQJClassDecl ct) {
    // get the appropriate proc for the type
    final Proc newObjectFunc = newObjectFuncForType.get(ct);
    // i = 0 is reserved to vtable reference
    int i = 1;

    addProcedure(newObjectFunc);
    setCurrentProc(newObjectFunc);

    BasicBlock init = newBasicBlock("init");
    addBasicBlock(init);
    setCurrentBlock(init);

    // allocate space for object
    TemporaryVar mallocResult = TemporaryVar("mallocRes");
    addInstruction(Alloc(mallocResult, Sizeof(getObjectStruct(ct))));
    TemporaryVar newObject = TemporaryVar(ct.getName() + "_object");
    addInstruction(Bitcast(newObject, getObjectPointerType(ct), VarRef(mallocResult)));

    // store a reference to a virtual table
    // it is always defined in the first position
    TemporaryVar vtableAddr = TemporaryVar("vtableAddr");
    addInstruction(GetElementPtr(vtableAddr,
        VarRef(newObject), OperandList(ConstInt(0), ConstInt(0))));
    addInstruction(Store(VarRef(vtableAddr), GlobalRef(virtualTableGlobal.get(ct))));

    // iterate through every field and store it
    for (NQJVarDecl v : getFieldsHierarchy(ct)) {
      TemporaryVar iAddr = TemporaryVar("i" + i);
      addInstruction(GetElementPtr(iAddr,
          VarRef(newObject), OperandList(ConstInt(0), ConstInt(i))));

      i++;
      Type type = translateType(v.getType());

      if (type instanceof TypeInt) {
        addInstruction(Store(VarRef(iAddr), ConstInt(0)));
      } else if (type instanceof TypeBool) {
        addInstruction(Store(VarRef(iAddr), ConstBool(false)));
      } else {
        // at this point nullpointer is sufficient for both arrays and objects
        addInstruction(Store(VarRef(iAddr), Nullpointer()));
      }

    }

    addInstruction(ReturnExpr(VarRef(newObject)));
  }

  private void finishNewArrayProc(Type componentType) {
    final Proc newArrayFunc = newArrayFuncForType.get(componentType);
    final Parameter size = newArrayFunc.getParameters().get(0);

    addProcedure(newArrayFunc);
    setCurrentProc(newArrayFunc);

    BasicBlock init = newBasicBlock("init");
    addBasicBlock(init);
    setCurrentBlock(init);
    TemporaryVar sizeLessThanZero = TemporaryVar("sizeLessThanZero");
    addInstruction(BinaryOperation(sizeLessThanZero,
        VarRef(size), Slt(), ConstInt(0)));
    BasicBlock negativeSize = newBasicBlock("negativeSize");
    BasicBlock goodSize = newBasicBlock("goodSize");
    currentBlock.add(Branch(VarRef(sizeLessThanZero), negativeSize, goodSize));

    addBasicBlock(negativeSize);
    negativeSize.add(HaltWithError("Array Size must be positive"));

    addBasicBlock(goodSize);
    setCurrentBlock(goodSize);

    // allocate space for the array
    TemporaryVar arraySizeInBytes = TemporaryVar("arraySizeInBytes");
    addInstruction(BinaryOperation(arraySizeInBytes,
        VarRef(size), Mul(), byteSize(componentType)));

    // 4 bytes for the length
    TemporaryVar arraySizeWithLen = TemporaryVar("arraySizeWitLen");
    addInstruction(BinaryOperation(arraySizeWithLen,
        VarRef(arraySizeInBytes), Add(), ConstInt(4)));

    TemporaryVar mallocResult = TemporaryVar("mallocRes");
    addInstruction(Alloc(mallocResult, VarRef(arraySizeWithLen)));
    TemporaryVar newArray = TemporaryVar("newArray");
    addInstruction(Bitcast(newArray,
        getArrayPointerType(componentType), VarRef(mallocResult)));

    // store the size
    TemporaryVar sizeAddr = TemporaryVar("sizeAddr");
    addInstruction(GetElementPtr(sizeAddr,
        VarRef(newArray), OperandList(ConstInt(0), ConstInt(0))));
    addInstruction(Store(VarRef(sizeAddr), VarRef(size)));

    // initialize Array with zeros:
    final BasicBlock loopStart = newBasicBlock("loopStart");
    final BasicBlock loopBody = newBasicBlock("loopBody");
    final BasicBlock loopEnd = newBasicBlock("loopEnd");
    final TemporaryVar iVar = TemporaryVar("iVar");
    currentBlock.add(Alloca(iVar, TypeInt()));
    currentBlock.add(Store(VarRef(iVar), ConstInt(0)));
    currentBlock.add(Jump(loopStart));

    // loop condition: while i < size`
    addBasicBlock(loopStart);
    setCurrentBlock(loopStart);
    final TemporaryVar i = TemporaryVar("i");
    final TemporaryVar nextI = TemporaryVar("nextI");
    loopStart.add(Load(i, VarRef(iVar)));
    TemporaryVar smallerSize = TemporaryVar("smallerSize");
    addInstruction(BinaryOperation(smallerSize,
        VarRef(i), Slt(), VarRef(size)));
    currentBlock.add(Branch(VarRef(smallerSize), loopBody, loopEnd));

    // loop body
    addBasicBlock(loopBody);
    setCurrentBlock(loopBody);
    // ar[i] = 0;
    final TemporaryVar iAddr = TemporaryVar("iAddr");
    addInstruction(GetElementPtr(iAddr,
        VarRef(newArray), OperandList(ConstInt(0), ConstInt(1), VarRef(i))));
    addInstruction(Store(VarRef(iAddr), defaultValue(componentType)));

    // nextI = i + 1;
    addInstruction(BinaryOperation(nextI, VarRef(i), Add(), ConstInt(1)));
    // store new value in i
    addInstruction(Store(VarRef(iVar), VarRef(nextI)));

    loopBody.add(Jump(loopStart));

    addBasicBlock(loopEnd);
    loopEnd.add(ReturnExpr(VarRef(newArray)));
  }

  private void translateClasses() {
    for (NQJClassDecl c : javaProg.getClassDecls()) {
      translateClass(c);
    }

    currentClass = null;
  }

  private void createObjectStructs() {
    for (NQJClassDecl c : javaProg.getClassDecls()) {
      createObjectStruct(c);
    }
  }

  private void createVirtualTables() {
    for (NQJClassDecl c : javaProg.getClassDecls()) {
      createVirtualTable(c);
    }
  }

  private void translateFunctions() {
    for (NQJFunctionDecl functionDecl : javaProg.getFunctionDecls()) {
      if (functionDecl.getName().equals("main")) {
        continue;
      }
      initFunction(functionDecl, null);
    }
    for (NQJFunctionDecl functionDecl : javaProg.getFunctionDecls()) {
      if (functionDecl.getName().equals("main")) {
        continue;
      }
      translateFunction(functionDecl, null);
    }

  }

  private void translateMainFunction() {
    NQJFunctionDecl f = null;
    for (NQJFunctionDecl functionDecl : javaProg.getFunctionDecls()) {
      if (functionDecl.getName().equals("main")) {
        f = functionDecl;
        break;
      }
    }

    if (f == null) {
      throw new IllegalStateException("Main function expected");
    }

    Proc proc = Proc("main", TypeInt(), ParameterList(), BasicBlockList());
    addProcedure(proc);
    functionImpl.put(f, proc);

    setCurrentProc(proc);
    BasicBlock initBlock = newBasicBlock("init");
    addBasicBlock(initBlock);
    setCurrentBlock(initBlock);

    // allocate space for the local variables
    allocaLocalVars(f.getMethodBody());

    // translate
    translateStmt(f.getMethodBody());
  }

  private void translateClass(NQJClassDecl c) {
    // it is important to initialize object structs before translating methods
    // otherwise it will throw null pointer exception
    initObjectStruct(c);
    currentClass = c;

    for (NQJFunctionDecl f : getMethodsHierarchy(c)) {
      initFunction(f, c);
    }

    for (NQJFunctionDecl f : getMethodsHierarchy(c)) {
      translateFunction(f, c);
    }

    // global tables are initialized in the last place
    initVirtualTable(c);
    createVirtualTableGlobal(c);
  }

  private void createObjectStruct(NQJClassDecl c) {
    // at this point just empty structs are created
    TypeStruct struct = TypeStruct(c.getName(), StructFieldList());
    objectStruct.put(c, struct);
  }

  private void initObjectStruct(NQJClassDecl c) {
    TypeStruct vtableStruct = virtualTableStruct.get(c);
    List<StructField> fields = new ArrayList<>();
    // first position is reserved for vtable reference
    fields.add(StructField(TypePointer(vtableStruct), "vtablePtr"));

    for (NQJVarDecl v : getFieldsHierarchy(c)) {
      Type type = translateType(v.getType());
      fields.add(StructField(type, v.getName()));
    }

    // update object struct field
    TypeStruct struct = getObjectStruct(c);
    struct.setFields(StructFieldList(fields));

    // add updated struct to prog struct types
    prog.getStructTypes().add(struct);
    objectStruct.put(c, struct);
  }


  private void createVirtualTable(NQJClassDecl c) {
    // at this point just empty structs are created
    TypeStruct struct = TypeStruct(c.getName() + "_table", StructFieldList());
    virtualTableStruct.put(c, struct);
  }

  private void initVirtualTable(NQJClassDecl c) {
    TypeStruct vt = virtualTableStruct.get(c);
    List<StructField> fields = new ArrayList<>();

    // initialization virtual table structs with values (fields)
    for (NQJFunctionDecl f : getMethodsHierarchy(c)) {
      List<Type> params = new ArrayList<>();
      params.add(TypePointer(getObjectStruct(c)));

      for (NQJVarDecl p : f.getFormalParameters()) {
        params.add(translateType(p.getType()));
      }

      TypeProc type = TypeProc(TypeRefList(params), translateType(f.getReturnType()));
      // again, suffix(_ + CLASS_NAME) is used to properly match function pointers with methods
      fields.add(StructField(TypePointer(type), f.getName() + "_" + c.getName()));
    }

    // update vtable fields
    vt.setFields(StructFieldList(fields));
    // add updated vtable to prog struct types
    virtualTableStruct.put(c, vt);
    prog.getStructTypes().add(vt);
  }

  private void createVirtualTableGlobal(NQJClassDecl c) {
    TypeStruct struct = virtualTableStruct.get(c);
    List<Const> values = new ArrayList<>();

    // collecting references of every method from the class considering inheritance
    for (NQJFunctionDecl f : getMethodsHierarchy(c)) {
      values.add(ProcedureRef(functionImpl.get(f)));
    }

    Global global = Global(struct, struct.getName(), true, ConstStruct(struct, ConstList(values)));

    virtualTableGlobal.put(c, global);
    prog.getGlobals().add(global);
  }


  private void initFunction(NQJFunctionDecl f, NQJClassDecl c) {
    Type returnType = translateType(f.getReturnType());
    // parameters collection using streams
    ParameterList params = f.getFormalParameters()
        .stream()
        .map(p -> Parameter(translateType(p.getType()), p.getName()))
        .collect(Collectors.toCollection(Ast::ParameterList));

    // suffix is used to distinguish methods from global functions and to determine affiliation
    String suffix = "";

    // if f is a class method, we need to add a pointer to an object as the first parameter
    if (c != null) {
      Type objectPointerType = getObjectPointerType(c);
      params.addFront(Parameter(objectPointerType, c.getName()));
      // alter suffix from empty to _NAME
      suffix = "_" + c.getName();
    }

    Proc proc = Proc(f.getName() + suffix, returnType, params, BasicBlockList());
    addProcedure(proc);
    functionImpl.put(f, proc);
  }

  private void translateFunction(NQJFunctionDecl m, NQJClassDecl c) {
    Proc proc = functionImpl.get(m);
    setCurrentProc(proc);
    BasicBlock initBlock = newBasicBlock("init");
    addBasicBlock(initBlock);
    setCurrentBlock(initBlock);

    localVarLocation.clear();
    nameVarDecl.clear();

    // if the function belong to a class
    if (c != null) {
      // allocate memory for object pointer
      TemporaryVar structPtr = TemporaryVar(c.getName() + "_ptr");
      addInstruction(Alloca(structPtr, getObjectPointerType(c)));
      addInstruction(Store(VarRef(structPtr), VarRef(proc.getParameters().get(0))));

      // get value of the pointer
      TemporaryVar structLoad = TemporaryVar("structLoad");
      addInstruction(Load(structLoad, VarRef(structPtr)));

      int i = 1;
      // store addresses of each field from the object pointer
      for (NQJVarDecl param : getFieldsHierarchy(c)) {
        TemporaryVar paramAddr = TemporaryVar(param.getName() + "_addr");
        addInstruction(GetElementPtr(paramAddr, VarRef(structLoad), OperandList(ConstInt(0), ConstInt(i++))));

        localVarLocation.put(param, paramAddr);
        nameVarDecl.put(param.getName(), param);
      }
    }

    // store copies of the parameters in Allocas, to make uniform read/write access possible
    int i = c == null ? 0 : 1;
    for (NQJVarDecl param : m.getFormalParameters()) {
      TemporaryVar v = TemporaryVar(param.getName());
      addInstruction(Alloca(v, translateType(param.getType())));
      addInstruction(Store(VarRef(v), VarRef(proc.getParameters().get(i))));
      localVarLocation.put(param, v);
      nameVarDecl.put(param.getName(), param);
      i++;
    }

    // allocate space for the local variables
    allocaLocalVars(m.getMethodBody());

    translateStmt(m.getMethodBody());
  }

  void translateStmt(NQJStatement s) {
    addInstruction(CommentInstr(sourceLine(s) + " start statement : " + printFirstline(s)));
    s.match(stmtTranslator);
    addInstruction(CommentInstr(sourceLine(s) + " end statement: " + printFirstline(s)));
  }

  int sourceLine(NQJElement e) {
    while (e != null) {
      if (e.getSourcePosition() != null) {
        return e.getSourcePosition().getLine();
      }
      e = e.getParent();
    }
    return 0;
  }

  private String printFirstline(NQJStatement s) {
    String str = print(s);
    str = str.replaceAll("\n.*", "");
    return str;
  }

  BasicBlock newBasicBlock(String name) {
    BasicBlock block = BasicBlock();
    block.setName(name);
    return block;
  }

  void addBasicBlock(BasicBlock block) {
    currentProcedure.getBasicBlocks().add(block);
  }

  BasicBlock getCurrentBlock() {
    return currentBlock;
  }

  void setCurrentBlock(BasicBlock currentBlock) {
    this.currentBlock = currentBlock;
  }


  void addProcedure(Proc proc) {
    prog.getProcedures().add(proc);
  }

  void setCurrentProc(Proc currentProc) {
    if (currentProc == null) {
      throw new RuntimeException("Cannot set proc to null");
    }
    this.currentProcedure = currentProc;
  }

  Proc getCurrentProcedure() {
    return currentProcedure;
  }

  private void allocaLocalVars(NQJBlock methodBody) {
    methodBody.accept(new NQJElement.DefaultVisitor() {
      @Override
      public void visit(NQJVarDecl localVar) {
        super.visit(localVar);
        TemporaryVar v = TemporaryVar(localVar.getName());
        addInstruction(Alloca(v, translateType(localVar.getType())));
        localVarLocation.put(localVar, v);
        nameVarDecl.put(localVar.getName(), localVar);
      }
    });
  }

  void addInstruction(Instruction instruction) {
    currentBlock.add(instruction);
  }

  // translateType was rewritten for NQJType, instead of binding to analysis.Type
  Type translateType(NQJType type) {
    Type result = translatedType.get(type);

    if (result == null) {
      if (type instanceof NQJTypeInt) {
        result = TypeInt();
      } else if (type instanceof NQJTypeBool) {
        result = TypeBool();
      } else if (type instanceof NQJTypeClass) {
        NQJTypeClass tc = (NQJTypeClass) type;
        result = TypePointer(getObjectStruct(getClassDeclByName(tc.getName())));
      } else if (type instanceof NQJTypeArray) {
        NQJTypeArray ta = (NQJTypeArray) type;
        result = TypePointer(getArrayStruct(translateType(ta.getComponentType())));
      } else {
        throw new RuntimeException("unhandled case " + type);
      }

      translatedType.put(type, result);
    }

    return result;
  }

  NQJClassDecl getClassDeclByFunctionDecl(NQJFunctionDecl f) {
    for (NQJClassDecl classDecl : javaProg.getClassDecls()) {
      for (NQJFunctionDecl functionDecl : classDecl.getMethods()) {
        if (f == functionDecl) {
          return classDecl;
        }
      }
    }

    throw new RuntimeException("Function " + f.getName() + " is not defined");

  }

  NQJClassDecl getClassDeclByName(String name) {
    for (NQJClassDecl classDecl : javaProg.getClassDecls()) {
      if (classDecl.getName().equals(name)) {
        return classDecl;
      }
    }

    throw new RuntimeException("Class " + name + " is not defined");
  }

  /**
   Used for handling field accesses
   */
  int getIndexOfFieldByName(String c, String f) {
    List<NQJVarDecl> fieldsHierarchy = getFieldsHierarchy(getClassDeclByName(c));

    for (int i = 0; i < fieldsHierarchy.size(); i++) {
      if (fieldsHierarchy.get(i).getName().equals(f)) {
        return i;
      }
    }

    throw new RuntimeException("Field " + f + " is not defined in class " + c);
  }

  /**
   Used for handling method calls
   */
  int getIndexOfMethodByName(String c, String m) {
    List<NQJFunctionDecl> methodsHierarchy = getMethodsHierarchy(getClassDeclByName(c));

    for (int i = 0; i < methodsHierarchy.size(); i++) {
      if (methodsHierarchy.get(i).getName().equals(m)) {
        return i;
      }
    }

    throw new RuntimeException("Method " + m + "does not exist in class " + c);
  }


  Parameter getThisParameter() {
    // in our case 'this' is always the first parameter
    return currentProcedure.getParameters().get(0);
  }

  Operand exprLvalue(NQJExprL e) {
    return e.match(exprLValue);
  }

  Operand exprRvalue(NQJExpr e) {
    return e.match(exprRValue);
  }

  void addNullcheck(Operand arrayAddr, String errorMessage) {
    TemporaryVar isNull = TemporaryVar("isNull");
    addInstruction(BinaryOperation(isNull, arrayAddr.copy(), Eq(), Nullpointer()));

    BasicBlock whenIsNull = newBasicBlock("whenIsNull");
    BasicBlock notNull = newBasicBlock("notNull");
    currentBlock.add(Branch(VarRef(isNull), whenIsNull, notNull));

    addBasicBlock(whenIsNull);
    whenIsNull.add(HaltWithError(errorMessage));

    addBasicBlock(notNull);
    setCurrentBlock(notNull);
  }

  Operand getArrayLen(Operand arrayAddr) {
    TemporaryVar addr = TemporaryVar("length_addr");
    addInstruction(GetElementPtr(addr,
        arrayAddr.copy(), OperandList(ConstInt(0), ConstInt(0))));
    TemporaryVar len = TemporaryVar("len");
    addInstruction(Load(len, VarRef(addr)));
    return VarRef(len);
  }

  public Operand getNewArrayFunc(Type componentType) {
    Proc proc = newArrayFuncForType.computeIfAbsent(componentType, this::createNewArrayProc);
    return ProcedureRef(proc);
  }

  private Proc createNewArrayProc(Type componentType) {
    Parameter size = Parameter(TypeInt(), "size");
    return Proc("newArray",
        getArrayPointerType(componentType), ParameterList(size), BasicBlockList());
  }

  private Type getArrayPointerType(Type componentType) {
    return TypePointer(getArrayStruct(componentType));
  }

  TypeStruct getArrayStruct(Type type) {
    return arrayStruct.computeIfAbsent(type, t -> {
      TypeStruct struct = TypeStruct("array_" + type, StructFieldList(
          StructField(TypeInt(), "length"),
          StructField(TypeArray(type, 0), "data")
      ));
      prog.getStructTypes().add(struct);
      return struct;
    });
  }

  public Operand getNewObjectFunc(NQJClassDecl c) {
    Proc proc = newObjectFuncForType.computeIfAbsent(c, k -> createNewObjectProc(c));
    return ProcedureRef(proc);
  }

  private Proc createNewObjectProc(NQJClassDecl c) {
    return Proc("newObjectConstructor", getObjectPointerType(c), ParameterList(), BasicBlockList());
  }

  Proc getProcByFuncDecl(NQJFunctionDecl f) {
    return functionImpl.get(f);
  }

  Type getObjectPointerType(NQJClassDecl c) {
    return TypePointer(getObjectStruct(c));
  }

  /**
   * Returns a hierarchy of methods from superclasses of a particular class
   */
  List<NQJFunctionDecl> getMethodsHierarchy(NQJClassDecl c) {
    List<NQJFunctionDecl> methods = new ArrayList<>(c.getMethods());
    Set<String> names = c.getMethods().stream()
        .map(NQJFunctionDecl::getName)
        .collect(Collectors.toSet());
    NQJClassDecl superClass = c.getDirectSuperClass();
    NQJClassDecl current = c;

    while (superClass != null) {
      for (NQJFunctionDecl f : superClass.getMethods()) {
        if (!names.contains(f.getName())) {
          methods.add(0, f);
          names.add(f.getName());
        }
      }

      current = superClass;
      superClass = current.getDirectSuperClass();
    }

    return methods;
  }

  /**
   * Returns a hierarchy of fields from superclasses of a particular class
   */
  List<NQJVarDecl> getFieldsHierarchy(NQJClassDecl c) {
    List<NQJVarDecl> fields = new ArrayList<>(c.getFields());
    Set<String> names = c.getFields().stream()
        .map(NQJVarDecl::getName)
        .collect(Collectors.toSet());
    NQJClassDecl superClass = c.getDirectSuperClass();
    NQJClassDecl current = c;

    while (superClass != null) {
      for (NQJVarDecl v : superClass.getFields()) {
        if (!names.contains(v.getName())) {
          fields.add(0, v);
          names.add(v.getName());
        }
      }

      current = superClass;
      superClass = current.getDirectSuperClass();
    }

    return fields;
  }

  TypeStruct getObjectStruct(NQJClassDecl c) {
    if (objectStruct.containsKey(c)) {
      return objectStruct.get(c);
    }

    throw new RuntimeException("ObjectStruct for class " + c.getName() + " is not defined");
  }

  // used in field access by this operator
  NQJClassDecl getCurrentClass() {
    return currentClass;
  }

  /**
   * Returns an argument list of either NQJFunctionCall or NQJFunctionCall
   */
  OperandList getArgumentsFromCall(NQJElement e) {
    NQJExprList arguments = e instanceof NQJFunctionCall ? ((NQJFunctionCall) e).getArguments()
        : ((NQJMethodCall) e).getArguments();
    NQJFunctionDecl functionDecl = e instanceof NQJFunctionCall ? ((NQJFunctionCall) e).getFunctionDeclaration()
        : ((NQJMethodCall) e).getFunctionDeclaration();
    OperandList args = OperandList();

    for (int i = 0; i < arguments.size(); i++) {
      Operand arg = exprRvalue(arguments.get(i));
      NQJVarDeclList formalParameters = functionDecl.getFormalParameters();
      arg = addCastIfNecessary(arg, translateType(
          formalParameters.get(i).getType())
      );
      args.add(arg);
    }

    return args;
  }

  Operand addCastIfNecessary(Operand value, Type expectedType) {
    if (expectedType.equalsType(value.calculateType()) || expectedType instanceof TypeStruct) {
      return value;
    }
    TemporaryVar castValue = TemporaryVar("castValue");
    addInstruction(Bitcast(castValue, expectedType, value));
    return VarRef(castValue);
  }

  BasicBlock unreachableBlock() {
    return BasicBlock();
  }

  Type getCurrentReturnType() {
    return currentProcedure.getReturnType();
  }

  public Proc loadFunctionProc(NQJFunctionDecl functionDeclaration) {
    return functionImpl.get(functionDeclaration);
  }

  /**
   * return the number of bytes required by the given type.
   */
  public Operand byteSize(Type type) {
    return type.match(new Type.Matcher<>() {
      @Override
      public Operand case_TypeByte(TypeByte typeByte) {
        return ConstInt(1);
      }

      @Override
      public Operand case_TypeArray(TypeArray typeArray) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeProc(TypeProc typeProc) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeInt(TypeInt typeInt) {
        return ConstInt(4);
      }

      @Override
      public Operand case_TypeStruct(TypeStruct typeStruct) {
        return Sizeof(typeStruct);
      }

      @Override
      public Operand case_TypeNullpointer(TypeNullpointer typeNullpointer) {
        return ConstInt(8);
      }

      @Override
      public Operand case_TypeVoid(TypeVoid typeVoid) {
        return ConstInt(0);
      }

      @Override
      public Operand case_TypeBool(TypeBool typeBool) {
        return ConstInt(1);
      }

      @Override
      public Operand case_TypePointer(TypePointer typePointer) {
        return ConstInt(8);
      }
    });
  }

  private Operand defaultValue(Type componentType) {
    return componentType.match(new Type.Matcher<>() {
      @Override
      public Operand case_TypeByte(TypeByte typeByte) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeArray(TypeArray typeArray) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeProc(TypeProc typeProc) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeInt(TypeInt typeInt) {
        return ConstInt(0);
      }

      @Override
      public Operand case_TypeStruct(TypeStruct typeStruct) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeNullpointer(TypeNullpointer typeNullpointer) {
        return Nullpointer();
      }

      @Override
      public Operand case_TypeVoid(TypeVoid typeVoid) {
        throw new RuntimeException("TODO implement");
      }

      @Override
      public Operand case_TypeBool(TypeBool typeBool) {
        return ConstBool(false);
      }

      @Override
      public Operand case_TypePointer(TypePointer typePointer) {
        return Nullpointer();
      }
    });
  }
}
