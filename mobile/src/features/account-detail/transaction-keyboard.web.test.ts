import { focusAdjacentTransaction, revealFocusedRow } from './transaction-keyboard.web';

/** A table row whose open button is focusable unless `disabled` (no DOM in jest-expo). */
function row(disabled = false, heading = false) {
  const button = { focus: jest.fn() };
  const self = {
    button,
    previousElementSibling: null as unknown,
    nextElementSibling: null as unknown,
    matches: (selector: string) => heading && selector === '[data-transaction-month]',
    querySelector: jest.fn((selector: string) =>
      selector === 'button[data-transaction-open]:enabled' && !disabled ? button : null,
    ),
  };
  return self;
}

function table(...rows: ReturnType<typeof row>[]) {
  rows.forEach((current, index) => {
    current.previousElementSibling = rows[index - 1] ?? null;
    current.nextElementSibling = rows[index + 1] ?? null;
  });
  return rows;
}

function press(from: ReturnType<typeof row>, key: string, modifiers: Partial<KeyboardEvent> = {}) {
  const preventDefault = jest.fn();
  focusAdjacentTransaction({
    key,
    altKey: false,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    ...modifiers,
    currentTarget: { closest: (selector: string) => (selector === 'tr' ? from : null) },
    preventDefault,
  } as unknown as Parameters<typeof focusAdjacentTransaction>[0]);
  return preventDefault;
}

describe('focusAdjacentTransaction (web transaction table, #494)', () => {
  it('moves focus to the next row with Arrow Down and the previous row with Arrow Up', () => {
    const [first, second, third] = table(row(), row(), row());

    expect(press(second, 'ArrowDown')).toHaveBeenCalledTimes(1);
    expect(third.button.focus).toHaveBeenCalledTimes(1);

    expect(press(second, 'ArrowUp')).toHaveBeenCalledTimes(1);
    expect(first.button.focus).toHaveBeenCalledTimes(1);
  });

  it('moves focus across month headings in both directions', () => {
    const [first, heading, second] = table(row(), row(true, true), row());
    press(first, 'ArrowDown');
    expect(second.button.focus).toHaveBeenCalledTimes(1);
    press(second, 'ArrowUp');
    expect(first.button.focus).toHaveBeenCalledTimes(1);
    expect(heading.button.focus).not.toHaveBeenCalled();
  });

  it('keeps focus at the first and last row without scrolling the page', () => {
    const [first, last] = table(row(), row());

    expect(press(first, 'ArrowUp')).toHaveBeenCalledTimes(1);
    expect(press(last, 'ArrowDown')).toHaveBeenCalledTimes(1);
    expect(first.button.focus).not.toHaveBeenCalled();
    expect(last.button.focus).not.toHaveBeenCalled();
  });

  it('does not focus a disabled open button', () => {
    const [first, second] = table(row(), row(true));

    press(first, 'ArrowDown');
    expect(second.button.focus).not.toHaveBeenCalled();
  });

  it('leaves other keys and modified arrows to the browser', () => {
    const [first, second] = table(row(), row());

    for (const key of ['Enter', ' ', 'Tab', 'ArrowLeft', 'Home']) {
      expect(press(first, key)).not.toHaveBeenCalled();
    }
    for (const modifier of ['altKey', 'ctrlKey', 'metaKey', 'shiftKey'] as const) {
      expect(press(first, 'ArrowDown', { [modifier]: true })).not.toHaveBeenCalled();
    }
    expect(second.button.focus).not.toHaveBeenCalled();
  });
});

describe('revealFocusedRow (web transaction table, #494)', () => {
  const inRow = { id: 'category button' };
  const focusEvent = (relatedTarget: unknown) => {
    const currentTarget = {
      contains: jest.fn((node: unknown) => node === inRow),
      scrollIntoView: jest.fn(),
    };
    revealFocusedRow({ currentTarget, relatedTarget } as unknown as Parameters<
      typeof revealFocusedRow
    >[0]);
    return currentTarget.scrollIntoView;
  };

  it('scrolls the row into view when focus enters it from outside', () => {
    expect(focusEvent({ id: 'previous row' })).toHaveBeenCalledWith({
      block: 'nearest',
      inline: 'nearest',
    });
    expect(focusEvent(null)).toHaveBeenCalledTimes(1);
  });

  it('does not scroll again when focus moves inside the same row', () => {
    expect(focusEvent(inRow)).not.toHaveBeenCalled();
  });
});
