declare const require: (id: string) => any;

const { loadOhosPlugin } = require('./hvigor-plugin-loader.cjs');
const { appTasks } = loadOhosPlugin();

export default {
  system: appTasks,
  plugins: []
};
