import type { ReactNode } from 'react';

import RootHtml from '@/app/+html';

jest.mock('expo-router/html', () => ({
  ScrollViewStyleReset: () => null,
  useServerDocumentContext: () => ({}),
}));

it('includes the app title in the initial web document head', () => {
  const { renderToStaticMarkup } = jest.requireActual<{
    renderToStaticMarkup: (node: ReactNode) => string;
  }>('react-dom/server');

  const html = renderToStaticMarkup(<RootHtml>{null}</RootHtml>);

  expect(html).toMatch(/<head>.*<title>TrackMyWealth<\/title>.*<\/head>/);
});
