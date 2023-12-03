/**
 * Wrong type in condition
 */

class A {

  B b;

  int foo() {

    if (b.bar()) {
      printInt(1);
    } else {
      printInt(0);
    }

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


