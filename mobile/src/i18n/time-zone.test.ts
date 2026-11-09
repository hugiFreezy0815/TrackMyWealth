import { applyDeviceTimeZone } from './time-zone';

/** A setter that knows only the given zone names, like the polyfill's zone data. */
const knownZones = (...zones: string[]) => {
  const applied: string[] = [];
  const set = (zone: string) => {
    if (!zones.includes(zone)) throw new RangeError('Invalid timeZoneName');
    applied.push(zone);
  };
  return { set, applied };
};

describe('applyDeviceTimeZone', () => {
  it('applies a zone the data knows', () => {
    const { set, applied } = knownZones('Europe/Zurich');
    expect(applyDeviceTimeZone(set, 'Europe/Zurich')).toBe('Europe/Zurich');
    expect(applied).toEqual(['Europe/Zurich']);
  });

  it('maps an alias such as GMT (iOS on UTC) to its Etc/ zone', () => {
    const { set } = knownZones('Etc/GMT');
    expect(applyDeviceTimeZone(set, 'GMT')).toBe('Etc/GMT');
  });

  it('never throws on an unknown zone and keeps the default', () => {
    const { set, applied } = knownZones('UTC');
    expect(applyDeviceTimeZone(set, 'Mars/Olympus')).toBeUndefined();
    expect(applied).toEqual([]);
  });

  it('does nothing when the engine DateTimeFormat is not polyfilled', () => {
    expect(applyDeviceTimeZone(undefined, 'GMT')).toBeUndefined();
  });
});
