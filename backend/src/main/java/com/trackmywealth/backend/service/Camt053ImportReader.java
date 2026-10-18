package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportAccountContext;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * #350: generic ISO 20022 camt.053 bank-to-customer statement reader. No bank-specific template or
 * account data is needed. The existing import services own access control, preview, deduplication,
 * statement snapshots and commit; this class only reads untrusted XML into their shared contract.
 *
 * <p>Only explicitly supported message versions are accepted. XML is bounded and all external
 * resolution is disabled. The original file is stored by ImportBatchService, never logged here.
 */
@Component
public class Camt053ImportReader {

  private static final Set<String> NAMESPACES =
      Set.of(
          "urn:iso:std:iso:20022:tech:xsd:camt.053.001.02",
          "urn:iso:std:iso:20022:tech:xsd:camt.053.001.04",
          "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08");
  private static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final int MAX_NODES = 200_000;
  private static final int MAX_DEPTH = 32;
  private static final int MAX_TEXT = 4096;
  private static final int MAX_ROWS = 10_000;
  private static final int MAX_DETAILS_PER_ENTRY = 200;
  private static final int MAX_DESCRIPTION = 512;

  /**
   * XML uploads bypass template selection, but arbitrary text uploads remain in the normal
   * CSV/PDF detection flow. A filename or media type is a hint, never trusted to validate XML.
   */
  public static boolean looksLikeXml(String fileName, String mediaType, byte[] content) {
    String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
    String type = mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.ROOT);
    if (name.endsWith(".xml") || type.contains("/xml") || type.contains("+xml")) {
      return true;
    }
    int pos = 0;
    // UTF-8 BOM and leading whitespace; no unbounded decoding.
    if (content.length >= 3
        && (content[0] & 0xff) == 0xef
        && (content[1] & 0xff) == 0xbb
        && (content[2] & 0xff) == 0xbf) {
      pos = 3;
    }
    while (pos < content.length
        && pos < 16
        && (content[pos] == ' ' || content[pos] == '\r' || content[pos] == '\n')) {
      pos++;
    }
    return pos + 5 <= content.length
        && content[pos] == '<'
        && content[pos + 1] == '?'
        && content[pos + 2] == 'x'
        && content[pos + 3] == 'm'
        && content[pos + 4] == 'l';
  }

  public ImportParseResult parse(byte[] content, ImportAccountContext account) {
    if (content.length == 0) {
      throw rejected("The XML statement is empty.");
    }
    if (content.length > MAX_BYTES) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_TOO_LARGE, "The XML statement exceeds the upload limit.");
    }
    Document document = secureDocument(content);
    int[] nodes = {0};
    validateSize(document.getDocumentElement(), 0, nodes);
    Element root = document.getDocumentElement();
    if (root == null
        || !is(root, "Document")
        || !NAMESPACES.contains(root.getNamespaceURI())) {
      throw rejected("Only supported ISO 20022 camt.053 statements can be imported.");
    }
    Element message = child(root, "BkToCstmrStmt");
    if (message == null) {
      throw rejected("The XML is not a camt.053 bank-to-customer statement.");
    }

    List<ParsedImportRow> rows = new ArrayList<>();
    List<ImportStatementBalances.Stated> balances = new ArrayList<>();
    Set<String> accountNumbers = new HashSet<>();
    int rowNo = 0;
    for (Element statement : children(message, "Stmt")) {
      Element accountNode = path(statement, "Acct", "Id", "IBAN");
      String iban = value(accountNode);
      if (iban != null) {
        Optional<String> normalized = AccountIdentifierService.normalize(iban, "IBAN");
        // Invalid account numbers are never treated as a positive account match.
        accountNumbers.add(normalized.orElse(""));
      }
      for (Element balance : children(statement, "Bal")) {
        if (!"CLBD".equals(value(path(balance, "Tp", "CdOrPrtry", "Cd")))) {
          continue;
        }
        try {
          Element amount = child(balance, "Amt");
          balances.add(
              new ImportStatementBalances.Stated(
                  date(value(path(balance, "Dt", "Dt"))),
                  signed(amount, value(child(balance, "CdtDbtInd"))),
                  currency(amount)));
        } catch (IllegalArgumentException exception) {
          // A closing balance that may belong to this account cannot be silently ignored.
          throw rejected("A closing booked balance could not be read.");
        }
      }
      for (Element entry : children(statement, "Ntry")) {
        if (++rowNo > MAX_ROWS) {
          throw new ImportFileRejectedException(
              ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS, "Too many CAMT entries.");
        }
        Map<String, String> raw = raw(entry);
        String status = value(child(entry, "Sts"));
        Element statusCode = path(entry, "Sts", "Cd");
        if (statusCode != null) {
          status = value(statusCode);
        }
        if (!"BOOK".equals(status)) {
          rows.add(ParsedImportRow.skipped(rowNo, raw, ImportRowErrorValues.ROW_FILTERED));
          continue;
        }
        try {
          Element amountNode = child(entry, "Amt");
          BigDecimal amount = signed(amountNode, value(child(entry, "CdtDbtInd")));
          String ccy = currency(amountNode);
          LocalDate booked = dateOf(entry, "BookgDt");
          LocalDate valued = child(entry, "ValDt") == null ? booked : dateOf(entry, "ValDt");
          String entryRef = safeRef(value(child(entry, "AcctSvcrRef")));
          String entryText = bounded(
              value(child(entry, "AddtlNtryInf")), MAX_DESCRIPTION);
          String bankCode = bankCode(entry);
          List<Element> details = children(child(entry, "NtryDtls"), "TxDtls");
          if (details.size() > MAX_DETAILS_PER_ENTRY) {
            rows.add(ambiguous(rowNo, raw));
            continue;
          }
          if (details.size() > 1) {
            List<CanonicalImportRow> parsedDetails = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Element detail : details) {
              Element detailAmount = path(detail, "AmtDtls", "TxAmt", "Amt");
              if (detailAmount == null) {
                // Some ISO versions put the amount under a plain TxDtls/Amt.
                detailAmount = child(detail, "Amt");
              }
              if (detailAmount == null || !ccy.equals(currency(detailAmount))) {
                throw new IllegalArgumentException("Missing detail amount or currency.");
              }
              String detailDirection = value(child(detail, "CdtDbtInd"));
              if (detailDirection == null) {
                throw new IllegalArgumentException("Missing detail direction.");
              }
              BigDecimal part = signed(detailAmount, detailDirection);
              total = total.add(part);
              parsedDetails.add(
                  canonical(booked, valued, part, ccy, detail, entryText, bankCode, null, account));
            }
            if (total.compareTo(amount) != 0) {
              rows.add(ambiguous(rowNo, raw));
              continue;
            }
            for (int i = 0; i < parsedDetails.size(); i++) {
              if (i > 0 && ++rowNo > MAX_ROWS) {
                throw new ImportFileRejectedException(
                    ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS, "Too many CAMT transactions.");
              }
              rows.add(ParsedImportRow.parsed(rowNo, raw, parsedDetails.get(i)));
            }
          } else {
            Element detail = details.isEmpty() ? null : details.get(0);
            rows.add(
                ParsedImportRow.parsed(
                    rowNo,
                    raw,
                    canonical(booked, valued, amount, ccy, detail, entryText, bankCode,
                        entryRef, account)));
          }
        } catch (IllegalArgumentException exception) {
          rows.add(ambiguous(rowNo, raw));
        }
      }
    }
    if (rowNo == 0) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS, "The CAMT statement has no entries.");
    }
    // A multi-account document is never trusted to select a single import destination.
    String single = accountNumbers.size() == 1 ? accountNumbers.iterator().next() : null;
    ImportFileAccountIdentifier identifier =
        new ImportFileAccountIdentifier("IBAN", single == null || single.isEmpty() ? null : single);
    return new ImportParseResult(
        List.of(),
        null,
        rows,
        identifier,
        new ImportStatementBalances(balances, 0));
  }

  private static ParsedImportRow ambiguous(int number, Map<String, String> raw) {
    return ParsedImportRow.error(
        number, raw, ImportRowErrorValues.CAMT_ENTRY_AMBIGUOUS, Map.of());
  }

  private static CanonicalImportRow canonical(
      LocalDate booked, LocalDate valued, BigDecimal amount, String currency, Element detail,
      String entryText, String bankCode, String entryRef, ImportAccountContext account) {
    String text = entryText;
    String party = null;
    String ref = entryRef;
    if (detail != null) {
      String detailText = value(path(detail, "RmtInf", "Ustrd"));
      if (detailText != null) {
        text = detailText;
      }
      String creditor = value(path(detail, "RltdPties", "Cdtr", "Nm"));
      String debtor = value(path(detail, "RltdPties", "Dbtr", "Nm"));
      party = amount.signum() < 0 ? creditor : debtor;
      if (party == null) party = amount.signum() < 0 ? debtor : creditor;
      String detailRef = safeRef(value(path(detail, "Refs", "AcctSvcrRef")));
      if (detailRef != null) ref = detailRef;
    }
    String type = account.creditCard()
        ? amount.signum() < 0 ? "CREDIT_CARD_PURCHASE" : "SETTLEMENT"
        : account.loan()
            ? amount.signum() < 0 ? "EXPENSE" : "DEBT_REPAYMENT"
            : amount.signum() < 0 ? "EXPENSE" : "INCOME";
    String description = text == null ? "Bank booking" : bounded(text, MAX_DESCRIPTION);
    return new CanonicalImportRow(
        booked, valued, amount, currency, type, description,
        bounded(party, 200), ref, null, bankCode, null);
  }

  private static String bankCode(Element entry) {
    String domain = value(path(entry, "BkTxCd", "Domn", "Cd"));
    String family = value(path(entry, "BkTxCd", "Domn", "Fmly", "Cd"));
    String subfamily = value(path(entry, "BkTxCd", "Domn", "Fmly", "SubFmlyCd"));
    if (domain != null) {
      return bounded(String.join("-", domain,
          family == null ? "" : family, subfamily == null ? "" : subfamily), 100);
    }
    return bounded(value(path(entry, "BkTxCd", "Prtry", "Cd")), 100);
  }

  private static Map<String, String> raw(Element entry) {
    Map<String, String> raw = new LinkedHashMap<>();
    // A bounded, non-sensitive preview field; original XML remains inside secured import_file.
    String description = value(child(entry, "AddtlNtryInf"));
    raw.put("CAMT entry", bounded(description == null ? "Bank booking" : description, MAX_DESCRIPTION));
    return raw;
  }

  private static String safeRef(String value) {
    if (value == null || "NOTPROVIDED".equalsIgnoreCase(value)) return null;
    return bounded(value, 140);
  }

  private static LocalDate dateOf(Element entry, String field) {
    Element holder = child(entry, field);
    if (holder == null) throw new IllegalArgumentException("Missing entry date.");
    Element value = child(holder, "Dt");
    if (value != null) return date(value(value));
    return dateTime(value(child(holder, "DtTm")));
  }

  private static LocalDate date(String date) {
    try {
      return LocalDate.parse(date);
    } catch (DateTimeParseException | NullPointerException exception) {
      throw new IllegalArgumentException("Invalid ISO date.", exception);
    }
  }

  private static LocalDate dateTime(String date) {
    try {
      return OffsetDateTime.parse(date).toLocalDate();
    } catch (DateTimeParseException | NullPointerException exception) {
      throw new IllegalArgumentException("Invalid ISO date-time.", exception);
    }
  }

  private static BigDecimal signed(Element amount, String direction) {
    if (amount == null) throw new IllegalArgumentException("Missing amount.");
    String str = value(amount);
    BigDecimal number;
    try {
      number = new BigDecimal(str);
    } catch (NumberFormatException | NullPointerException exception) {
      throw new IllegalArgumentException("Invalid amount.", exception);
    }
    if (number.signum() < 0 || number.scale() > 4 || number.precision() > 20) {
      throw new IllegalArgumentException("Amount exceeds storage precision.");
    }
    if ("DBIT".equals(direction)) return number.negate();
    if ("CRDT".equals(direction)) return number;
    throw new IllegalArgumentException("Missing credit/debit indicator.");
  }

  private static String currency(Element amount) {
    if (amount == null) throw new IllegalArgumentException("Missing currency.");
    String code = amount.getAttribute("Ccy");
    try {
      if (!code.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency.");
      Currency.getInstance(code);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid currency.", exception);
    }
    return code;
  }

  private static String bounded(String value, int max) {
    if (value == null) return null;
    String trimmed = value.strip();
    if (trimmed.length() > max) return trimmed.substring(0, max);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String value(Element element) {
    return element == null ? null : bounded(element.getTextContent(), MAX_TEXT);
  }

  private static Element path(Element root, String... names) {
    Element node = root;
    for (String name : names) {
      node = child(node, name);
      if (node == null) return null;
    }
    return node;
  }

  private static Element child(Element parent, String name) {
    if (parent == null) return null;
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node current = children.item(i);
      if (current instanceof Element element && is(element, name)) return element;
    }
    return null;
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    if (parent == null) return found;
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node current = children.item(i);
      if (current instanceof Element element && is(element, name)) found.add(element);
    }
    return found;
  }

  private static boolean is(Element element, String name) {
    return name.equals(element.getLocalName());
  }

  private static void validateSize(Node node, int depth, int[] counter) {
    if (depth > MAX_DEPTH || ++counter[0] > MAX_NODES) {
      throw rejected("The XML statement exceeds the structural limits.");
    }
    if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
        && node.getNodeValue() != null
        && node.getNodeValue().length() > MAX_TEXT) {
      throw rejected("The XML statement contains an overlong text field.");
    }
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) validateSize(children.item(i), depth + 1, counter);
  }

  private static Document secureDocument(byte[] content) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
    } catch (ParserConfigurationException exception) {
      throw new IllegalStateException("Secure XML parsing could not be configured.", exception);
    } catch (SAXException | java.io.IOException exception) {
      // Do not include a parser message: it can repeat the statement's private contents.
      throw rejected("The XML statement is malformed or contains prohibited XML features.");
    }
  }

  private static ImportFileRejectedException rejected(String detail) {
    return new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_MALFORMED, detail);
  }
}
