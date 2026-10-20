package archfixture.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;

/** Fixture (#373): a controller answering with Spring Data's Page, bare and wrapped. */
public class PageLeakingController {

  public Page<String> bare() {
    return Page.empty();
  }

  public ResponseEntity<Page<String>> wrapped() {
    return ResponseEntity.ok(Page.empty());
  }
}
