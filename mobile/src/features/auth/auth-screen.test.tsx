import { TextInput } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { NetworkError } from '@/api/client';
import { saveServerUrl } from '@/api/server-url';
import { withIntl } from '@/i18n/test-utils';

import { AuthScreen } from './auth-screen';
import { cancelMfa, signIn, verifyMfa } from './session';

jest.mock('@/global.css', () => ({}));
jest.mock('./session', () => ({
  signIn: jest.fn(async () => {}),
  verifyMfa: jest.fn(async () => {}),
  cancelMfa: jest.fn(),
}));
jest.mock('@/api/server-url', () => {
  const actual = jest.requireActual('@/api/server-url');
  return {
    ...actual,
    saveServerUrl: jest.fn(async (input: string) => actual.normalizeServerUrl(input)),
  };
});
jest.mock('expo-secure-store', () => ({}));

describe('AuthScreen', () => {
  beforeEach(() => jest.clearAllMocks());

  it('saves the chosen server before sending the credentials to it', async () => {
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.changeText(screen.getByLabelText('Server'), 'https://wealth.example.com/');
    await fireEvent.changeText(screen.getByLabelText('Email'), ' demo@example.com ');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(signIn).toHaveBeenCalledWith('demo@example.com', 'secret'));
    expect(saveServerUrl).toHaveBeenCalledWith('https://wealth.example.com/');
    expect(jest.mocked(saveServerUrl).mock.invocationCallOrder[0]).toBeLessThan(
      jest.mocked(signIn).mock.invocationCallOrder[0],
    );
  });

  it('moves focus from Email to Password when Return is pressed', async () => {
    const focus = jest.spyOn(TextInput.prototype, 'focus');
    try {
      await render(withIntl(<AuthScreen mfaRequired={false} />));
      const email = screen.getByLabelText('Email');

      expect(email.props.returnKeyType).toBe('next');
      expect(email.props.submitBehavior).toBe('submit');
      await fireEvent(email, 'submitEditing');

      expect(focus).toHaveBeenCalledTimes(1);
      expect(focus.mock.contexts[0]).toMatchObject({
        props: { accessibilityLabel: 'Password' },
      });
      expect(signIn).not.toHaveBeenCalled();
    } finally {
      focus.mockRestore();
    }
  });

  it('submits from Password on Return and ignores another Return while busy', async () => {
    let finishSignIn: (() => void) | undefined;
    jest.mocked(signIn).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSignIn = resolve;
      }),
    );
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.changeText(screen.getByLabelText('Email'), 'demo@example.com');
    const password = screen.getByLabelText('Password');
    await fireEvent.changeText(password, 'secret');
    await fireEvent(password, 'submitEditing');
    await waitFor(() => expect(signIn).toHaveBeenCalledWith('demo@example.com', 'secret'));

    await fireEvent(password, 'submitEditing');

    expect(signIn).toHaveBeenCalledTimes(1);
    expect(saveServerUrl).toHaveBeenCalledTimes(1);
    finishSignIn?.();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).not.toBeBusy());
  });

  it('labels every field visibly, not only for screen readers', async () => {
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    for (const label of ['Server', 'Email', 'Password']) {
      expect(screen.getByText(label)).toBeTruthy();
    }
  });

  it('never sends credentials to a server without HTTPS', async () => {
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.changeText(screen.getByLabelText('Server'), 'http://wealth.example.com');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The server must use https://');
    expect(signIn).not.toHaveBeenCalled();
  });

  it('shows one generic message for any failed sign-in', async () => {
    jest.mocked(signIn).mockRejectedValueOnce(new Error('401'));
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not sign in/);
  });

  it('says when the server could not be reached instead of blaming the credentials', async () => {
    jest.mocked(signIn).mockRejectedValueOnce(new NetworkError('could not reach the server'));
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not reach the server/);
  });

  it('forgets the password once the server accepted it', async () => {
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.changeText(screen.getByLabelText('Email'), 'demo@example.com');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByLabelText('Password').props.value).toBe(''));
  });

  it('keeps the password for another try when sign-in failed', async () => {
    jest.mocked(signIn).mockRejectedValueOnce(new Error('401'));
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Password').props.value).toBe('secret');
  });

  it('asks only for the authenticator code during MFA and can start over', async () => {
    jest.mocked(verifyMfa).mockRejectedValueOnce(new Error('401'));
    await render(withIntl(<AuthScreen mfaRequired />));

    expect(screen.queryByLabelText('Password')).toBeNull();
    await fireEvent.changeText(screen.getByLabelText('Authenticator code'), '123456');
    await fireEvent.press(screen.getByRole('button', { name: 'Verify code' }));
    await waitFor(() => expect(verifyMfa).toHaveBeenCalledWith('123456'));
    expect(await screen.findByRole('alert')).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Start over' }));

    expect(cancelMfa).toHaveBeenCalled();
  });

  it('explains a malformed server address in the member language', async () => {
    await render(withIntl(<AuthScreen mfaRequired={false} />, 'de'));

    await fireEvent.changeText(screen.getByLabelText('Server'), 'not an address');
    await fireEvent.press(screen.getByRole('button', { name: 'Anmelden' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Gib die Serveradresse ein/);
    expect(signIn).not.toHaveBeenCalled();
  });

  it('renders every sign-in text from the German catalogue', async () => {
    await render(withIntl(<AuthScreen mfaRequired={false} />, 'de'));

    for (const label of ['E-Mail', 'Passwort']) {
      expect(screen.getByLabelText(label)).toBeTruthy();
    }
    expect(screen.getByRole('header')).toHaveTextContent('Anmelden');
  });

  it('keeps the button name and announces its state while the request runs', async () => {
    jest.mocked(signIn).mockReturnValueOnce(new Promise(() => {}));
    await render(withIntl(<AuthScreen mfaRequired={false} />));

    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    const button = screen.getByRole('button', { name: 'Sign in' });
    expect(button).toBeBusy();
    expect(button).toBeDisabled();
  });
});
