/**
 * calling not existing method
 */

class A {

  int foo() {
    return 0;
  }

}

class B extends A {
  int bar() {
    return 0;
  }
}



int main() {
  A a;
  a = new A();
  a.bar();

  return 0;
}


