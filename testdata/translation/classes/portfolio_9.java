/**
 * Class use in function
 */

class A {

  int k;

  int init() {
    k = 50;
    return k;
  }

}

int bar() {
  A a;
  a = new A();
  a.init();
  return a.k;
}



int main() {
  printInt(bar());
  return 0;
}


