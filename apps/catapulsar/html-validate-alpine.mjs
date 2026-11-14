// html-validate plugin for Alpine.js (orch.md HTML-5, decision 94). It tells the validator what Alpine adds at
// run time, so rules see the page as the browser does:
// - `:attr` / `x-bind:attr` → the attribute `attr`, with a dynamic value (e.g. `:aria-label`, `:for`, `:id`);
// - `x-text` / `x-html` → the element has (dynamic) text content.
// Event handlers (`@click`, `x-on:*`) and the other `x-*` directives are left as they are.
import { DynamicValue } from 'html-validate';

const BIND = /^(?::|x-bind:)(.+)$/;

/**
 * Yields every attribute, plus the bound attribute an Alpine `:attr` stands for.
 * @param {import('html-validate').AttributeData} attr attribute as written
 * @yields {import('html-validate').AttributeData} attributes the element has
 */
function* processAttribute(attr) {
  yield attr;
  const bind = BIND.exec(attr.key);
  if (bind && typeof attr.value === 'string') {
    yield { key: bind[1], value: new DynamicValue(attr.value), quote: attr.quote, originalAttribute: attr.key };
  }
}

/**
 * Marks elements whose text Alpine sets as having text content.
 * @param {import('html-validate').HtmlElement} node element being parsed
 */
function processElement(node) {
  for (const name of ['x-text', 'x-html']) {
    const attr = node.getAttribute(name);
    if (attr) {
      node.appendText({ dynamic: name }, node.location);
    }
  }
}

/**
 * Transformer for HTML files using Alpine: the source is kept as it is, with the hooks above.
 * @param {import('html-validate').Source} source the file
 * @returns {import('html-validate').Source[]} the same file, with Alpine-aware hooks
 */
function alpine(source) {
  return [{ ...source, hooks: { processAttribute, processElement } }];
}
alpine.api = 1;

export default {
  name: 'alpine',
  transformer: { default: alpine },
};
