declare const require: (id: string) => any;

const { loadOhosPlugin } = require('../hvigor-plugin-loader.cjs');
const { hapTasks } = loadOhosPlugin();

export default {
  system: hapTasks,
  plugins: []
};
