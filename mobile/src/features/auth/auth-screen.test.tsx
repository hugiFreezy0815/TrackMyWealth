import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { NetworkError } from '@/api/client';
import { saveServerUrl } from '@/api/server-url';

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
    await render(<AuthScreen mfaRequired={false} />);

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

  it('labels every field visibly, not only for screen readers', async () => {
    await render(<AuthScreen mfaRequired={false} />);

    for (const label of ['Server', 'Email', 'Password']) {
      expect(screen.getByText(label)).toBeTruthy();
    }
  });

  it('never sends credentials to a server without HTTPS', async () => {
    await render(<AuthScreen mfaRequired={false} />);

    await fireEvent.changeText(screen.getByLabelText('Server'), 'http://wealth.example.com');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The server must use https://');
    expect(signIn).not.toHaveBeenCalled();
  });

  it('shows one generic message for any failed sign-in', async () => {
    jest.mocked(signIn).mockRejectedValueOnce(new Error('401'));
    await render(<AuthScreen mfaRequired={false} />);

    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not sign in/);
  });

  it('says when the server could not be reached instead of blaming the credentials', async () => {
    jest.mocked(signIn).mockRejectedValueOnce(new NetworkError('could not reach the server'));
    await render(<AuthScreen mfaRequired={false} />);

    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not reach the server/);
  });

  it('forgets the password once the server accepted it', async () => {
    await render(<AuthScreen mfaRequired={false} />);

    await fireEvent.changeText(screen.getByLabelText('Email'), 'demo@example.com');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByLabelText('Password').props.value).toBe(''));
  });

  it('keeps the password for another try when sign-in failed', async () => {
    jest.mocked(signIn).mockRejectedValueOnce(new Error('401'));
    await render(<AuthScreen mfaRequired={false} />);

    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Password').props.value).toBe('secret');
  });

  it('asks only for the authenticator code during MFA and can start over', async () => {
    jest.mocked(verifyMfa).mockRejectedValueOnce(new Error('401'));
    await render(<AuthScreen mfaRequired />);

    expect(screen.queryByLabelText('Password')).toBeNull();
    await fireEvent.changeText(screen.getByLabelText('Authenticator code'), '123456');
    await fireEvent.press(screen.getByRole('button', { name: 'Verify code' }));
    await waitFor(() => expect(verifyMfa).toHaveBeenCalledWith('123456'));
    expect(await screen.findByRole('alert')).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Start over' }));

    expect(cancelMfa).toHaveBeenCalled();
  });
});
