"""静态检查 p0_trial.py 对内部函数的调用是否符合各自签名。

该工具只能在维护窗口内执行，参数写错会直接浪费一次生产中断。这里的 AST 检查不导入模块
（因此不需要 pymysql 或 GPU），可以在本地即时发现关键字参数拼错、必填参数缺失这类低级错误。
"""

import ast
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parent / 'p0_trial.py'


def signatures(tree):
    """收集模块级函数的位置参数、关键字参数名与是否接受可变位置参数。"""
    found = {}
    for node in tree.body:
        if isinstance(node, ast.FunctionDef):
            found[node.name] = {
                'positional': [arg.arg for arg in node.args.args],
                'required': len(node.args.args) - len(node.args.defaults),
                'keywordOnly': [arg.arg for arg in node.args.kwonlyargs],
                'variadic': node.args.vararg is not None,
            }
    return found


class TrialSignatureTest(unittest.TestCase):
    def setUp(self):
        self.tree = ast.parse(SOURCE.read_text())
        self.signatures = signatures(self.tree)

    def test_internal_calls_match_signatures(self):
        problems = []
        for node in ast.walk(self.tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name):
                continue
            declared = self.signatures.get(node.func.id)
            if declared is None:
                continue
            allowed = set(declared['positional']) | set(declared['keywordOnly'])
            for keyword in node.keywords:
                if keyword.arg is not None and keyword.arg not in allowed:
                    problems.append(f'{node.func.id}: unknown keyword {keyword.arg}')
            if len(node.args) > len(declared['positional']) and not declared['variadic']:
                problems.append(f'{node.func.id}: too many positional arguments')
            for keyword in node.keywords:
                if keyword.arg in declared['positional']:
                    index = declared['positional'].index(keyword.arg)
                    if index < len(node.args):
                        problems.append(f'{node.func.id}: duplicate argument {keyword.arg}')
        self.assertEqual(problems, [])

    def test_required_arguments_are_always_passed(self):
        problems = []
        for node in ast.walk(self.tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name):
                continue
            declared = self.signatures.get(node.func.id)
            if declared is None:
                continue
            supplied = set(declared['positional'][:len(node.args)])
            supplied.update(keyword.arg for keyword in node.keywords if keyword.arg)
            for name in declared['positional'][:declared['required']]:
                if name not in supplied:
                    problems.append(f'{node.func.id}: missing required argument {name}')
        self.assertEqual(problems, [])

    def test_signatures_are_discoverable(self):
        # 若文件被重命名或结构变化，本测试应显式失败而不是静默跳过。
        for name in ('run_one', 'start_unit', 'open_window', 'main', 'restore'):
            self.assertIn(name, self.signatures)


if __name__ == '__main__':
    unittest.main()
