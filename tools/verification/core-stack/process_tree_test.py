"""回归评审 P2：终止父进程后，后代必须退出且释放 stdout 管道。"""
import ctypes
import os
import subprocess
import sys
import unittest
from process_tree import stop_tree

def alive(pid):
    if os.name == 'nt':
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.restype = ctypes.c_void_p
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        code = ctypes.c_ulong()
        kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code))
        kernel.CloseHandle(ctypes.c_void_p(handle))
        return code.value == 259
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False

class ProcessTreeTest(unittest.TestCase):
    def test_descendant_releases_inherited_output_pipe(self):
        program = "import subprocess,sys,time; child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']); print(child.pid,flush=True); time.sleep(30)"
        parent = subprocess.Popen([sys.executable, '-u', '-c', program], stdout=subprocess.PIPE, text=True, start_new_session=(os.name != 'nt'))
        try:
            child = int(parent.stdout.readline().strip())
            self.assertTrue(alive(child))
            stop_tree(parent)
            parent.communicate(timeout=5)
            self.assertFalse(alive(child), '子进程不应继续持有输出管道')
        finally:
            stop_tree(parent)

if __name__ == '__main__':
    unittest.main()
