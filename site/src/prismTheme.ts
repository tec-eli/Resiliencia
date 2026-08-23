import type {PrismTheme} from 'prism-react-renderer';

// Matches the blueprint palette used by the JavaPlayground editor and the homepage's
// Composition code panel, so fenced code blocks in docs read as the same visual system.
const blueprintPrismTheme: PrismTheme = {
  plain: {
    color: '#f0f0f0',
    backgroundColor: '#001c40',
  },
  styles: [
    {
      types: ['comment', 'prolog', 'doctype', 'cdata'],
      style: {color: 'rgba(240, 240, 240, 0.5)', fontStyle: 'italic'},
    },
    {
      types: ['keyword', 'attr-name'],
      style: {color: '#8ecbff'},
    },
    {
      types: ['string', 'char', 'attr-value', 'inserted'],
      style: {color: '#ffe08a'},
    },
    {
      types: ['class-name', 'tag', 'builtin'],
      style: {color: '#7de89a'},
    },
    {
      types: ['number', 'boolean', 'constant'],
      style: {color: '#ffb08a'},
    },
    {
      types: ['function', 'property', 'variable'],
      style: {color: '#d9c8ff'},
    },
    {
      types: ['operator', 'punctuation', 'plain'],
      style: {color: 'rgba(240, 240, 240, 0.64)'},
    },
    {
      types: ['deleted'],
      style: {color: '#ff8a8a'},
    },
  ],
};

export default blueprintPrismTheme;
