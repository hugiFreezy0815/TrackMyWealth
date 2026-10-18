package com.trackmywealth.backend.camt;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * #350: reads one ISO 20022 CAMT XML document - a {@code camt.053} bank-to-customer statement or a
 * {@code camt.052} account report - into its statements' accounts, balances and entries, as
 * written: nothing is interpreted or validated here beyond the structure, so a malformed value
 * becomes a row error of that entry, not a rejected file.
 *
 * <p>The message is recognised by its namespace alone ({@code
 * urn:iso:std:iso:20022:tech:xsd:camt.05x.001.nn}), and only the versions {@link
 * #SUPPORTED_VERSIONS} are read; any other document is a {@link CamtUnsupportedException}.
 *
 * <p>The file is untrusted. It is read by streaming StAX with the JDK's own implementation: a
 * document type declaration or an entity reference rejects it, and no external resource is ever
 * resolved, so XXE and entity expansion cannot happen. Only the subtrees of an account, a balance
 * or an entry are kept, each bounded; everything else is read past. Depth, text runs, elements and
 * entries are counted as they are read ({@link CamtReadBudget}), so a limit stops the read before
 * it holds more. The XML itself is never logged.
 */
public final class CamtXmlReader {

  public static final String STATEMENT = "camt.053";
  public static final String REPORT = "camt.052";
  public static final Set<String> SUPPORTED_VERSIONS = Set.of("001.02", "001.04", "001.08");
  // A collective entry may list many transactions; past this many, it is not split (its booked
  // amount is still exact), and every one kept is a bounded subtree.
  public static final int MAX_TRANSACTIONS_PER_ENTRY = 500;
  // A real entry nests 12 levels; supplementary data may add a few.
  static final int MAX_DEPTH = 40;
  // The longest CAMT text is a Max2048Text; whitespace between elements counts in its own run.
  static final int MAX_TEXT_LENGTH = 4_096;
  static final int MAX_ENTRY_ELEMENTS = 50_000;
  static final int MAX_PART_ELEMENTS = 1_000;

  private static final Pattern NAMESPACE =
      Pattern.compile("urn:iso:std:iso:20022:tech:xsd:(camt\\.05[23])\\.(\\d{3}\\.\\d{2})");
  private static final Map<String, String> MESSAGE_ELEMENTS =
      Map.of(STATEMENT, "BkToCstmrStmt", REPORT, "BkToCstmrAcctRpt");
  private static final Map<String, String> STATEMENT_ELEMENTS =
      Map.of(STATEMENT, "Stmt", REPORT, "Rpt");
  private static final int ROOT_DEPTH = 1;
  private static final int MESSAGE_DEPTH = 2;
  private static final int STATEMENT_DEPTH = 3;
  private static final int PART_DEPTH = 4;
  private static final String MALFORMED = "The XML is malformed.";
  private static final String AMOUNT = "Amt";
  private static final String CODE = "Cd";
  private static final String DATE = "Dt";
  private static final String NAME = "Nm";
  private static final String PROPRIETARY = "Prtry";
  private static final String DOMAIN = "Domn";
  private static final String FAMILY = "Fmly";
  private static final String STATUS = "Sts";
  private static final String TRANSACTION = "TxDtls";
  private static final String ENTRY_DETAILS = "NtryDtls";
  private static final String AMOUNT_DETAILS = "AmtDtls";
  private static final String TRANSACTION_AMOUNT = "TxAmt";
  private static final String EXCHANGE = "CcyXchg";
  private static final String RATE = "XchgRate";
  private static final String CREDIT_DEBIT = "CdtDbtInd";
  private static final String SERVICER_REFERENCE = "AcctSvcrRef";
  private static final String BANK_CODE = "BkTxCd";
  private static final String RELATED_PARTIES = "RltdPties";
  private static final String IDENTIFICATION = "Id";

  private final XMLStreamReader reader;
  private final CamtReadBudget budget;
  private String namespace;
  private int depth;
  private int textRun;

  private CamtXmlReader(XMLStreamReader reader, CamtReadBudget budget) {
    this.reader = reader;
    this.budget = budget;
  }

  /**
   * The message {@code xml} holds.
   *
   * @throws CamtUnsupportedException well-formed XML, but no supported CAMT message
   * @throws CamtFormatException malformed XML, or XML with a document type declaration or an entity
   *     reference
   * @throws CamtLimitException the document, or all documents read with {@code budget} together,
   *     hold more than one import may
   */
  static CamtMessage read(byte[] xml, CamtReadBudget budget)
      throws CamtFormatException, CamtLimitException {
    try {
      XMLStreamReader reader = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
      CamtMessage message = new CamtXmlReader(reader, budget).message();
      // Frees the parser's buffers; the byte array needs no closing.
      reader.close();
      return message;
    } catch (XMLStreamException e) {
      throw new CamtFormatException(MALFORMED, e);
    }
  }

  private static XMLInputFactory factory() {
    // The JDK's implementation, whatever else is on the classpath, so these settings are known to
    // apply. A factory per read: configured factories are not documented as thread-safe.
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.IS_COALESCING, false);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setXMLResolver(
        (publicId, systemId, baseUri, referencingNamespace) -> {
          throw new XMLStreamException("No external resource may be resolved.");
        });
    return factory;
  }

  private CamtMessage message() throws XMLStreamException, CamtFormatException, CamtLimitException {
    String messageType = null;
    String version = null;
    List<CamtStatement> statements = new ArrayList<>();
    StatementParts parts = new StatementParts();
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        enter();
        if (depth == ROOT_DEPTH) {
          Matcher root = root();
          messageType = root.group(1);
          version = root.group(2);
        } else if (depth == MESSAGE_DEPTH) {
          if (!isOwn(MESSAGE_ELEMENTS.get(messageType))) {
            throw new CamtUnsupportedException(
                "The document's message element does not match its namespace.");
          }
        } else if (depth == STATEMENT_DEPTH && isOwn(STATEMENT_ELEMENTS.get(messageType))) {
          parts.open();
        } else if (depth == PART_DEPTH && parts.isOpen() && isOwnNamespace()) {
          readPart(parts);
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        if (depth == STATEMENT_DEPTH && parts.isOpen()) {
          statements.add(parts.close());
        }
        leave();
      } else {
        other(event, null);
      }
    }
    return new CamtMessage(messageType, version, statements);
  }

  // The root element: a Document in the namespace of a supported message and version.
  private Matcher root() throws CamtUnsupportedException {
    String uri = reader.getNamespaceURI();
    Matcher matcher = NAMESPACE.matcher(uri == null ? "" : uri);
    if (!"Document".equals(reader.getLocalName())
        || !matcher.matches()
        || !SUPPORTED_VERSIONS.contains(matcher.group(2))) {
      throw new CamtUnsupportedException(
          "The XML is not a supported ISO 20022 message: only camt.053 and camt.052, versions "
              + "001.02, 001.04 and 001.08, can be imported.");
    }
    namespace = uri;
    return matcher;
  }

  // A child of a statement the import needs is kept whole; any other is read past.
  private void readPart(StatementParts parts)
      throws XMLStreamException, CamtFormatException, CamtLimitException {
    switch (reader.getLocalName()) {
      case "Acct" -> parts.account(capture(MAX_PART_ELEMENTS));
      case "Bal" -> parts.balances.add(balance(capture(MAX_PART_ELEMENTS)));
      case "Ntry" -> {
        budget.countEntry();
        parts.entries.add(entry(capture(MAX_ENTRY_ELEMENTS)));
      }
      default -> {
        // Its descendants are read past by the caller's loop, and counted there.
      }
    }
  }

  // The element the reader stands on, through its end, as a node: only elements of the message's
  // namespace, at most limit of them, and at most MAX_TRANSACTIONS_PER_ENTRY transaction details.
  private CamtNode capture(int limit)
      throws XMLStreamException, CamtFormatException, CamtLimitException {
    CamtNode top = node();
    CamtNode skipped = CamtNode.skipped();
    Deque<CamtNode> open = new ArrayDeque<>();
    open.push(top);
    int kept = 1;
    int transactions = 0;
    while (!open.isEmpty()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        enter();
        CamtNode parent = open.peek();
        boolean keep = parent.isKept() && isOwnNamespace();
        if (keep && TRANSACTION.equals(reader.getLocalName())) {
          keep = transactions < MAX_TRANSACTIONS_PER_ENTRY;
          if (keep) {
            transactions++;
          } else {
            top.truncate();
          }
        }
        if (keep) {
          kept++;
          if (kept > limit) {
            throw new CamtLimitException(
                "A statement element holds more than " + limit + " elements.");
          }
          CamtNode child = node();
          parent.add(child);
          open.push(child);
        } else {
          open.push(skipped);
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        open.pop();
        leave();
      } else {
        other(event, open.peek());
      }
    }
    return top;
  }

  private CamtNode node() throws CamtLimitException {
    String ccy = reader.getAttributeValue(null, "Ccy");
    if (ccy != null && ccy.length() > MAX_TEXT_LENGTH) {
      throw overlongText();
    }
    return new CamtNode(reader.getLocalName(), ccy);
  }

  // Text, comments and processing instructions; a DTD or an entity reference rejects the file.
  private void other(int event, CamtNode into) throws CamtFormatException, CamtLimitException {
    switch (event) {
      case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
        int length = reader.getTextLength();
        textRun += length;
        if (textRun > MAX_TEXT_LENGTH) {
          throw overlongText();
        }
        if (into != null) {
          into.appendText(reader.getTextCharacters(), reader.getTextStart(), length);
        }
      }
      case XMLStreamConstants.DTD ->
          throw new CamtFormatException(
              "The XML has a document type declaration, which a CAMT message never has.");
      case XMLStreamConstants.ENTITY_REFERENCE ->
          throw new CamtFormatException(
              "The XML references an entity, which a CAMT message never does.");
      default -> {
        // Comments, processing instructions and the document's start and end carry no data.
      }
    }
  }

  private void enter() throws CamtLimitException {
    depth++;
    textRun = 0;
    if (depth > MAX_DEPTH) {
      throw new CamtLimitException("The XML nests elements deeper than " + MAX_DEPTH + " levels.");
    }
    budget.countElement();
  }

  private void leave() {
    depth--;
    textRun = 0;
  }

  private boolean isOwnNamespace() {
    return namespace != null && namespace.equals(reader.getNamespaceURI());
  }

  private boolean isOwn(String localName) {
    return isOwnNamespace() && localName.equals(reader.getLocalName());
  }

  private static CamtLimitException overlongText() {
    return new CamtLimitException(
        "The XML holds a text longer than " + MAX_TEXT_LENGTH + " characters.");
  }

  // --- from the kept subtrees to the records -----------------------------------------------------

  private static CamtBalance balance(CamtNode node) {
    return new CamtBalance(
        node.valueAt("Tp", "CdOrPrtry", CODE),
        amountOf(node.child(AMOUNT)),
        node.valueAt(CREDIT_DEBIT),
        dateOf(node.child(DATE)));
  }

  private static CamtEntry entry(CamtNode node) {
    // A code up to .001.04, a choice of a code or a proprietary status from .001.06.
    String status = node.valueAt(STATUS);
    if (status == null) {
      status = node.valueAt(STATUS, CODE);
    }
    if (status == null) {
      status = node.valueAt(STATUS, PROPRIETARY);
    }
    List<CamtNode> details = node.children(ENTRY_DETAILS);
    return new CamtEntry(
        amountOf(node.child(AMOUNT)),
        node.valueAt(CREDIT_DEBIT),
        node.valueAt("RvslInd"),
        status,
        dateOf(node.child("BookgDt")),
        dateOf(node.child("ValDt")),
        node.valueAt(SERVICER_REFERENCE),
        node.valueAt("AddtlNtryInf"),
        bankCode(node.child(BANK_CODE)),
        details.stream()
            .map(detail -> detail.valueAt("Btch", "NbOfTxs"))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null),
        details.stream()
            .flatMap(detail -> detail.children(TRANSACTION).stream())
            .map(CamtXmlReader::transactionOf)
            .toList(),
        node.truncated());
  }

  private static CamtTransaction transactionOf(CamtNode node) {
    // The detail's own amount from .001.04; before, only the transaction amount of its details.
    CamtNode amount = node.child(AMOUNT);
    if (amount == null) {
      amount = node.at(AMOUNT_DETAILS, TRANSACTION_AMOUNT, AMOUNT);
    }
    CamtNode instructed = node.at(AMOUNT_DETAILS, "InstdAmt");
    String rate = instructed == null ? null : instructed.valueAt(EXCHANGE, RATE);
    if (rate == null) {
      rate = node.valueAt(AMOUNT_DETAILS, TRANSACTION_AMOUNT, EXCHANGE, RATE);
    }
    List<String> remittance = new ArrayList<>();
    CamtNode information = node.child("RmtInf");
    if (information != null) {
      information.children("Ustrd").stream()
          .map(CamtNode::value)
          .filter(Objects::nonNull)
          .forEach(remittance::add);
      information.children("Strd").stream()
          .map(structured -> structured.valueAt("CdtrRefInf", "Ref"))
          .filter(Objects::nonNull)
          .forEach(remittance::add);
    }
    return new CamtTransaction(
        amountOf(amount),
        node.valueAt(CREDIT_DEBIT),
        instructed == null ? null : amountOf(instructed.child(AMOUNT)),
        rate,
        node.valueAt("Refs", SERVICER_REFERENCE),
        node.valueAt("Refs", "EndToEndId"),
        party(node.at(RELATED_PARTIES, "Cdtr")),
        party(node.at(RELATED_PARTIES, "Dbtr")),
        remittance,
        node.valueAt("AddtlTxInf"),
        bankCode(node.child(BANK_CODE)));
  }

  // A party's name: directly up to .001.04, inside Pty (a choice with an agent) from .001.08.
  private static String party(CamtNode node) {
    if (node == null) {
      return null;
    }
    String name = node.valueAt(NAME);
    return name != null ? name : node.valueAt("Pty", NAME);
  }

  private static CamtBankCode bankCode(CamtNode node) {
    if (node == null) {
      return null;
    }
    return new CamtBankCode(
        node.valueAt(DOMAIN, CODE),
        node.valueAt(DOMAIN, FAMILY, CODE),
        node.valueAt(DOMAIN, FAMILY, "SubFmlyCd"),
        node.valueAt(PROPRIETARY, CODE),
        node.valueAt(PROPRIETARY, "Issr"));
  }

  private static CamtAmount amountOf(CamtNode node) {
    return node == null ? null : new CamtAmount(node.value(), node.currency());
  }

  private static CamtDate dateOf(CamtNode node) {
    return node == null ? null : new CamtDate(node.valueAt(DATE), node.valueAt("DtTm"));
  }

  // What the open statement's kept children add up to, until its end element; reused per statement.
  private static final class StatementParts {

    private boolean active;
    // Its first account only: a statement is of one account.
    private final List<CamtNode> accounts = new ArrayList<>();
    private final List<CamtBalance> balances = new ArrayList<>();
    private final List<CamtEntry> entries = new ArrayList<>();

    private void open() {
      active = true;
      accounts.clear();
      balances.clear();
      entries.clear();
    }

    private boolean isOpen() {
      return active;
    }

    private void account(CamtNode account) {
      if (accounts.isEmpty()) {
        accounts.add(account);
      }
    }

    private CamtStatement close() {
      active = false;
      CamtNode account = accounts.isEmpty() ? null : accounts.get(0);
      return new CamtStatement(
          account == null ? null : account.valueAt(IDENTIFICATION, "IBAN"),
          account == null ? null : account.valueAt(IDENTIFICATION, "Othr", IDENTIFICATION),
          balances,
          entries);
    }
  }
}
