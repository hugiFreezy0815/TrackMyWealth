import {
  dismissUpdateRequired,
  reportUpdateRequired,
  subscribeUpdateRequired,
  updateRequiredSnapshot,
} from './update-required';

describe('update required (#576)', () => {
  afterEach(() => dismissUpdateRequired());

  it('is set by a report and cleared by a dismissal, telling each subscriber once', () => {
    const listener = jest.fn();
    const unsubscribe = subscribeUpdateRequired(listener);

    expect(updateRequiredSnapshot()).toBe(false);
    reportUpdateRequired();
    reportUpdateRequired();
    expect(updateRequiredSnapshot()).toBe(true);
    expect(listener).toHaveBeenCalledTimes(1);

    dismissUpdateRequired();
    dismissUpdateRequired();
    expect(updateRequiredSnapshot()).toBe(false);
    expect(listener).toHaveBeenCalledTimes(2);

    unsubscribe();
    reportUpdateRequired();
    expect(listener).toHaveBeenCalledTimes(2);
  });
});
