// "Replace Stream API chain with loop" "true"

import java.util.List;

import static java.util.Arrays.asList;

public class Main {
  public static void test(List<List<String>> list) {
    if(list.stream().filter(x -> x != null).flatMap(x -> x.stream()).allMat<caret>ch(x -> x.startsWith("a"))) {
      System.out.println("ok");
    }
  }

  public static void main(String[] args) {
    System.out.println(test(asList(asList(), asList("a"), asList("b", "c"))));
    System.out.println(test(asList(asList(), asList("d"), asList("b", "c"))));
  }
}