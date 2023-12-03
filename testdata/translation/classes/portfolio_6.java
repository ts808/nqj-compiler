/**
 * Field shadowing
 */

class A {

  int b;

  int foo() {
    b = 5;
    this.b = 10;

    if (this.b - b == 5) {
      printInt(1);
    } else {
      printInt(0);
    }

    return 0;
  }

}



  int main() {
    A a;
    a = new A();
    a.foo();

    return 0;
  }


