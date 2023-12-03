package analysis;

import notquitejava.ast.NQJClassDecl;


public class TypeClass extends Type {

  private final String name;
  private final NameTable nameTable;

  public TypeClass(String name, NameTable nameTable) {
    this.name = name;
    this.nameTable = nameTable;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean isSubtypeOf(Type other) {
    return isEqualToType(other) || nameTable.isSubclass(name, other.getName());
  }

  @Override
  public boolean isEqualToType(Type other) {
    if (other.getName() == null) {
      return false;
    }

    return other.getName().equals(this.name);
  }
}
