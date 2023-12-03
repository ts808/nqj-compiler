/**
 * Calling non-existing method
 *
 */

class A {
  int foo() {
    return 0;
  }
}

class B {
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