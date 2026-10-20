package archfixture.controller;

import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Fixture (#373): paging parameters that skip the sort whitelist, next to one that keeps it - only
 * the first two may fail the rule.
 */
public class UncheckedSortController {

  public String pageable(Pageable pageable) {
    return pageable.toString();
  }

  public String sort(Sort sort) {
    return sort.toString();
  }

  public String whitelisted(
      @Paged(sort = @SortField(name = "name"), defaultSort = "name,asc") Pageable pageable) {
    return pageable.toString();
  }
}
