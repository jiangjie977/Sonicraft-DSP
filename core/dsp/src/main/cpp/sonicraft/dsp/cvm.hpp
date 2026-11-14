/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * SCVM —— Sonicraft 自研「类 C 栈式虚拟机」。
 * 用于自定义脚本效果器（见 docs/SCRIPT_EFFECT_DESIGN.md）。
 *
 * 设计目标（对齐 JamesDSP/EEL2 的实时特性）：
 *  - 逐样本执行，无 GC、执行期零堆分配（栈/全局内存全部预分配）；
 *  - 类 C 语法子集，编译成紧凑字节码后由栈式 VM 解释；
 *  - 指令预算防脚本死循环，坏脚本只丢当前样本、不拖垮音频线程；
 *  - 零第三方依赖。
 *
 * 语言子集：
 *   类型: float / int（都按浮点处理）；全局标量与固定长度数组。
 *   函数: 仅 void init()、void sample()。
 *   语句: 块、声明、赋值(= += -= *= /=)、if/else、while、for、break、continue、return。
 *   表达式: || && == != < <= > >= + - * / %、一元 -、!、函数调用、数组下标。
 *   内建: sin/cos/tan/asin/acos/atan/atan2/exp/log/log10/pow/sqrt/abs/floor/ceil/
 *         round/min/max/tanh/fmod/clamp/sign/saturate。
 *   全局量: spl0/spl1（当前样本）、srate、slider1..slider8（参数）。
 */
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace sonicraft::dsp::cvm {

// ---------------------------------------------------------------------------
// 字节码
// ---------------------------------------------------------------------------
enum class Op : std::uint8_t {
  Const,     // a=-, b=value
  GetG,      // a=slot
  SetG,      // a=slot
  GetL,      // a=local slot
  SetL,      // a=local slot
  LoadArr,   // a=base slot, b=size（全局数组，gmem）
  StoreArr,  // a=base slot, b=size（全局数组，gmem）
  LoadLArr,  // a=base local slot, b=size（局部数组，locals）
  StoreLArr, // a=base local slot, b=size（局部数组，locals）
  Dup,       // 复制栈顶
  Add, Sub, Mul, Div, Mod, Neg, Not,
  CmpEq, CmpNe, CmpLt, CmpLe, CmpGt, CmpGe,
  Jmp,       // a=target
  Jz,        // a=target, pop cond
  Jnz,       // a=target, pop cond
  Call,      // a=builtin id, b=argc
  CallUser,  // a=func id, b=argc（用户函数调用）
  Pop,       // 丢弃栈顶
  Ret,       // 结束当前函数 / 返回调用点
};

struct Instr {
  Op op = Op::Ret;
  int a = 0;
  double b = 0.0;
};

struct Global {
  std::string name;
  bool is_array = false;
  int size = 1;   // 标量=1，数组=元素数
  int slot = 0;   // gmem 起始槽
};

struct Func {
  std::string name;
  int param_count = 0;
  int local_count = 0;   // 参数占 0..param_count-1
  int code_offset = 0;   // 在 sample_code 中的绝对偏移
};

// 脚本可声明的非滑块控件（toggle/select/button）
enum class CtrlType { Toggle, Select, Button, List };

struct Control {
  CtrlType type = CtrlType::Toggle;
  int index = 0;                     // 0..7（toggle1 → 0）
  std::string name;                  // "toggle1" / "select2" / "button1" / "list1"
  std::string label;                 // UI 显示名（缺省用 name）
  std::vector<std::string> options;  // select / list 使用
  float default_value = 0.0f;        // 声明的默认值
  bool has_default = false;
};

struct Program {
  std::vector<Instr> init_code;
  std::vector<Instr> sample_code;
  std::vector<Global> globals;
  int gmem_slots = 0;        // 全局存储槽总数
  int sample_local_count = 0;
  int init_local_count = 0;
  // bit i = slider(i+1) 是否被脚本引用（供 UI 自动生成滑块）
  std::array<bool, 8> used_sliders{};
  // slider 范围（脚本用 `// sliderN: min, max` 声明；默认 0..1）
  std::array<float, 8> slider_min{};
  std::array<float, 8> slider_max{};
  // slider 默认值（`// sliderN: min, max, default`）
  std::array<float, 8> slider_default{};
  std::array<bool, 8> slider_has_default{};
  std::vector<Func> funcs;
  // 脚本声明的 toggle/select/button（slider 仍走上面的 mask + 范围）
  std::vector<Control> controls;
  // 块处理段：block() 的入口（拼在 sample_code 末尾）与局部槽数
  int block_offset = -1;
  int block_local_count = 0;
};

// 预注册全局槽（与脚本可见名对应）
constexpr int kSlotSpl0 = 0;
constexpr int kSlotSpl1 = 1;
constexpr int kSlotSrate = 2;
constexpr int kSlotSlider1 = 3;    // slider1..8  → 3..10
constexpr int kSlotToggle1 = 11;   // toggle1..8  → 11..18
constexpr int kSlotSelect1 = 19;   // select1..8  → 19..26
constexpr int kSlotButton1 = 27;   // button1..8  → 27..34
constexpr int kSlotList1 = 35;     // list1..8    → 35..42（多选，值为位掩码）
constexpr int kReservedSlots = 43;

// ---------------------------------------------------------------------------
// 词法
// ---------------------------------------------------------------------------
enum class Tk {
  End, Num, Id,
  Plus, Minus, Star, Slash, Percent,
  PlusPlus, MinusMinus,
  Assign, PlusEq, MinusEq, StarEq, SlashEq,
  EqEq, NotEq, Lt, Le, Gt, Ge, AndAnd, OrOr, Not,
  Question, Colon,
  LParen, RParen, LBrace, RBrace, LBracket, RBracket,
  Comma, Semi,
  KwIf, KwElse, KwWhile, KwFor, KwBreak, KwContinue, KwReturn,
  KwVoid, KwFloat, KwInt, KwFunction,
};

struct Token {
  Tk k = Tk::End;
  double num = 0.0;
  std::string text;
  int line = 1;
  int col = 1;
};

struct CompileError {
  int line = 0;
  std::string msg;
};

class Lexer {
 public:
  explicit Lexer(const std::string& src) : s_(src) {}

  std::vector<Token> Run() {
    std::vector<Token> out;
    while (true) {
      SkipSpaceAndComments();
      if (i_ >= s_.size()) { Push(out, Tk::End); break; }
      const char c = s_[i_];
      if (IsDigit(c)) { out.push_back(Number()); continue; }
      if (IsIdStart(c)) { out.push_back(Identifier()); continue; }
      out.push_back(OpToken());
    }
    return out;
  }

 private:
  const std::string& s_;
  std::size_t i_ = 0;
  int line_ = 1;
  int col_ = 1;

  static bool IsDigit(char c) { return c >= '0' && c <= '9'; }
  static bool IsIdStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }
  static bool IsIdChar(char c) { return IsIdStart(c) || IsDigit(c); }

  void Push(std::vector<Token>& out, Tk k) {
    Token t; t.k = k; t.line = line_; t.col = col_; out.push_back(std::move(t));
  }

  char Peek(int n = 0) const {
    const std::size_t p = i_ + static_cast<std::size_t>(n);
    return p < s_.size() ? s_[p] : '\0';
  }
  void Advance() {
    if (i_ < s_.size()) {
      if (s_[i_] == '\n') { ++line_; col_ = 1; } else { ++col_; }
      ++i_;
    }
  }

  void SkipSpaceAndComments() {
    while (i_ < s_.size()) {
      const char c = s_[i_];
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { Advance(); continue; }
      if (c == '/' && Peek(1) == '/') {
        while (i_ < s_.size() && s_[i_] != '\n') Advance();
        continue;
      }
      if (c == '/' && Peek(1) == '*') {
        Advance(); Advance();
        while (i_ < s_.size() && !(s_[i_] == '*' && Peek(1) == '/')) Advance();
        if (i_ < s_.size()) { Advance(); Advance(); }
        continue;
      }
      break;
    }
  }

  Token Number() {
    Token t; t.k = Tk::Num; t.line = line_; t.col = col_;
    const std::size_t start = i_;
    while (IsDigit(Peek()) || Peek() == '.') Advance();
    t.num = std::strtod(s_.substr(start, i_ - start).c_str(), nullptr);
    return t;
  }

  Token Identifier() {
    Token t; t.k = Tk::Id; t.line = line_; t.col = col_;
    const std::size_t start = i_;
    while (IsIdChar(Peek())) Advance();
    t.text = s_.substr(start, i_ - start);
    if (t.text == "if") t.k = Tk::KwIf;
    else if (t.text == "else") t.k = Tk::KwElse;
    else if (t.text == "while") t.k = Tk::KwWhile;
    else if (t.text == "for") t.k = Tk::KwFor;
    else if (t.text == "break") t.k = Tk::KwBreak;
    else if (t.text == "continue") t.k = Tk::KwContinue;
    else if (t.text == "return") t.k = Tk::KwReturn;
    else if (t.text == "void") t.k = Tk::KwVoid;
    else if (t.text == "float") t.k = Tk::KwFloat;
    else if (t.text == "int") t.k = Tk::KwInt;
    else if (t.text == "function") t.k = Tk::KwFunction;
    return t;
  }

  Token OpToken() {
    Token t; t.line = line_; t.col = col_;
    const char c = s_[i_];
    const char n = Peek(1);
    auto two = [&](Tk k) { Advance(); Advance(); t.k = k; return t; };
    auto one = [&](Tk k) { Advance(); t.k = k; return t; };
    switch (c) {
      case '+': if (n == '=') return two(Tk::PlusEq); if (n == '+') return two(Tk::PlusPlus); return one(Tk::Plus);
      case '-': if (n == '=') return two(Tk::MinusEq); if (n == '-') return two(Tk::MinusMinus); return one(Tk::Minus);
      case '*': if (n == '=') return two(Tk::StarEq); return one(Tk::Star);
      case '/': if (n == '=') return two(Tk::SlashEq); return one(Tk::Slash);
      case '%': return one(Tk::Percent);
      case '=': if (n == '=') return two(Tk::EqEq); return one(Tk::Assign);
      case '!': if (n == '=') return two(Tk::NotEq); return one(Tk::Not);
      case '<': if (n == '=') return two(Tk::Le); return one(Tk::Lt);
      case '>': if (n == '=') return two(Tk::Ge); return one(Tk::Gt);
      case '&': if (n == '&') return two(Tk::AndAnd); break;
      case '|': if (n == '|') return two(Tk::OrOr); break;
      case '(': return one(Tk::LParen);
      case ')': return one(Tk::RParen);
      case '{': return one(Tk::LBrace);
      case '}': return one(Tk::RBrace);
      case '[': return one(Tk::LBracket);
      case ']': return one(Tk::RBracket);
      case ',': return one(Tk::Comma);
      case ';': return one(Tk::Semi);
      case '?': return one(Tk::Question);
      case ':': return one(Tk::Colon);
      default: break;
    }
    // 非 ASCII 字节（如中文标点）绝不能拼进错误串：会变成非法 UTF-8，
    // 经 JNI NewStringUTF 传递给 Java 时会直接 abort（表现为"闪退不报错"）。
    const unsigned char uc = static_cast<unsigned char>(c);
    if (uc >= 0x80) {
      char buf[8];
      std::snprintf(buf, sizeof(buf), "0x%02X", static_cast<unsigned>(uc));
      throw CompileError{line_, std::string("non-ASCII character (byte ") + buf +
                                     "): use ASCII punctuation"};
    }
    throw CompileError{line_, std::string("unexpected character '") + c + "'"};
  }
};

// ---------------------------------------------------------------------------
// AST
// ---------------------------------------------------------------------------
enum class NT {
  Num, Var, Index, Assign, Bin, Un, Call, Block, Decl, If, While, For,
  Return, Break, Continue, Expr, Empty, Ternary,
};

struct Node;
using NodePtr = std::unique_ptr<Node>;

struct Node {
  NT t = NT::Empty;
  double num = 0.0;
  std::string name;
  std::string op;          // 二元运算符 / 赋值运算符 / 内建函数名
  std::vector<NodePtr> kids;
  int line = 0;
  int slot = 0;            // 变量槽（全局或局部）
  int size = 1;            // 数组元素数
  bool is_local = false;
  bool is_array = false;
  int func_id = -1;        // Call 节点：>=0 表示用户函数
};

// 符号表条目（不含任何拥有型成员，可安全拷贝）
struct Sym {
  int slot = 0;
  int size = 1;
  bool is_local = false;
  bool is_array = false;
};

// ---------------------------------------------------------------------------
// 解析 + 字节码生成
// ---------------------------------------------------------------------------
// 内建函数参数个数（用于编译期校验，避免运行期静默取错位）
inline int BuiltinArity(int id) {
  switch (id) {
    case 6:   // atan2
    case 10:  // pow
    case 16:  // min
    case 17:  // max
    case 19:  // fmod
    case 26:  // dl_write(id, x)
    case 27:  // dl_read(id, samples)
    case 28:  // dl_clear(id)
    case 30:  // blk_get(ch, i)
    case 33:  // fft_load(i, v)
    case 40:  // conv_setup(id, taps)
    case 42:  // conv_run(id, ch)
    case 44:  // convfft_load(k, v)
      return 2;
    case 20:  // clamp
    case 31:  // blk_set(ch, i, v)
    case 37:  // fft_set(i, re, im)
    case 41:  // conv_load(id, k, v)
      return 3;
    case 23:  // bq_lp(id, f, q, x)
    case 24:  // bq_hp(id, f, q, x)
      return 4;
    case 25:  // bq_peak(id, f, q, db, x)
      return 5;
    case 29:  // blk_size()
    case 34:  // fft_forward()
    case 38:  // fft_inverse()
      return 0;
    default:
      return 1;
  }
}

class Parser {
 public:
  Parser(std::vector<Token> tk, Program& prog) : tk_(std::move(tk)), prog_(prog) {
    InitBuiltins();
  }

  /** .eel 子集模式：允许隐式全局与 ( ... ) 块。 */
  void SetEelMode(bool v) { eel_ = v; }

  void Run() {
    while (!Check(Tk::End)) {
      if (eel_ && Check(Tk::KwFunction)) {
        ParseEelFunction();
        continue;
      }
      const bool is_type = Check(Tk::KwVoid) || Check(Tk::KwFloat) || Check(Tk::KwInt);
      if (!is_type) throw CompileError{Peek().line, "expected declaration or function"};
      // 类型 id '(' → 函数；否则全局声明
      if (Peek(1).k == Tk::Id && Peek(2).k == Tk::LParen) {
        ParseTopFunction();
      } else if (Check(Tk::KwVoid)) {
        throw CompileError{Peek().line, "expected function"};
      } else {
        ParseGlobalDecl();
      }
    }
    // 全局初始化在前、init() 体在后（C 语义）。
    // 注意：init 体内部的 Jmp/Jz/Jnz 偏移是相对 init_body_ 的，拼接后要 +pre_init_ 长度，
    // 否则 init 里的 while/if 会跳到全局初始化段，表现为「循环次数失控 / 条件失效」。
    prog_.init_code = pre_init_;
    {
      const int pre_len = static_cast<int>(pre_init_.size());
      std::vector<Instr> init_final = init_body_;
      for (auto& in : init_final) {
        if (in.op == Op::Jmp || in.op == Op::Jz || in.op == Op::Jnz) {
          in.a += pre_len;
        }
      }
      prog_.init_code.insert(prog_.init_code.end(), init_final.begin(), init_final.end());
    }
    // 拼接 sample_code：入口在前、用户函数在后。
    // 关键：函数体的内部跳转（Jmp/Jz/Jnz）在 func_code_ 里是相对偏移，
    // 拼接后要整体 +entry_len 重定位；否则 if/while/for 会跳到函数开头，
    // 递归场景直接死循环到指令预算耗尽（表现为结果 0）。
    // 入口必须有 Ret：否则「只声明 block()、没有 sample()」时，
    // block 代码会落在 sample_code 偏移 0，RunSample() 每样本都重跑整块处理 → 爆音。
    if (entry_code_.empty()) entry_code_.push_back(Instr{Op::Ret, 0, 0});
    const int entry_len = static_cast<int>(entry_code_.size());
    prog_.funcs.clear();
    for (auto& f : funcs_) {
      Func rec = f;
      rec.code_offset = entry_len + f.code_offset;
      prog_.funcs.push_back(rec);
    }
    std::vector<Instr> funcs_final = func_code_;
    for (auto& in : funcs_final) {
      if (in.op == Op::Jmp || in.op == Op::Jz || in.op == Op::Jnz) {
        in.a += entry_len;
      }
    }
    prog_.sample_code = entry_code_;
    prog_.sample_code.insert(prog_.sample_code.end(), funcs_final.begin(), funcs_final.end());
    // block() 段拼在最后：其内部跳转需再偏移 entry_len + func_len
    if (!block_body_.empty()) {
      const int func_len = static_cast<int>(funcs_final.size());
      std::vector<Instr> blk = block_body_;
      for (auto& in : blk) {
        if (in.op == Op::Jmp || in.op == Op::Jz || in.op == Op::Jnz) {
          in.a += entry_len + func_len;
        }
      }
      prog_.block_offset = entry_len + func_len;
      prog_.sample_code.insert(prog_.sample_code.end(), blk.begin(), blk.end());
    }
  }

 private:
  std::vector<Token> tk_;
  std::size_t pos_ = 0;
  Program& prog_;
  std::unordered_map<std::string, Sym> globals_;    // name -> 全局信息
  std::unordered_map<std::string, Sym> locals_;     // 当前函数局部
  int local_count_ = 0;
  std::vector<Instr>* code_ = nullptr;
  bool eel_ = false;
  std::vector<Instr> pre_init_;                     // 全局初始化（先于 init() 体）
  std::vector<Instr> init_body_;                    // init() 体
  std::vector<Instr> entry_code_;                   // sample 入口
  std::vector<Instr> block_body_;                   // block() 段
  std::vector<Instr> func_code_;                    // 用户函数体
  std::vector<Func> funcs_;                         // 函数表（offset 为 func_code_ 内偏移）
  std::unordered_map<std::string, int> func_index_;  // 名字 → 函数 id

  // 预注册内置全局：spl0/spl1/srate/slider1..8（槽号与 kSlot* 对齐）
  void InitBuiltins() {
    auto add = [&](const std::string& nm, int slot) {
      Sym s; s.slot = slot; s.size = 1; s.is_local = false; s.is_array = false;
      globals_[nm] = s;
      Global g; g.name = nm; g.is_array = false; g.size = 1; g.slot = slot;
      prog_.globals.push_back(g);
    };
    add("spl0", kSlotSpl0);
    add("spl1", kSlotSpl1);
    add("srate", kSlotSrate);
    for (int i = 0; i < 8; ++i) {
      add("slider" + std::to_string(i + 1), kSlotSlider1 + i);
      add("toggle" + std::to_string(i + 1), kSlotToggle1 + i);
      add("select" + std::to_string(i + 1), kSlotSelect1 + i);
      add("button" + std::to_string(i + 1), kSlotButton1 + i);
      add("list" + std::to_string(i + 1), kSlotList1 + i);
    }
  }
  struct LoopCtx { std::vector<int> breaks; std::vector<int> continues; int cont_target = -1; };
  std::vector<LoopCtx> loops_;

  // ---- token 辅助 ----
  const Token& Peek(int n = 0) const {
    const std::size_t p = pos_ + static_cast<std::size_t>(n);
    return p < tk_.size() ? tk_[p] : tk_.back();
  }
  bool Check(Tk k) const { return Peek().k == k; }
  bool Match(Tk k) { if (Check(k)) { ++pos_; return true; } return false; }
  const Token& Advance() { const Token& t = Peek(); ++pos_; return t; }
  void Expect(Tk k, const char* what) {
    if (!Check(k)) throw CompileError{Peek().line, std::string("expected ") + what};
    ++pos_;
  }

  void Emit(Op op, int a = 0, double b = 0.0) {
    Instr in; in.op = op; in.a = a; in.b = b; code_->push_back(in);
  }
  int Here() const { return static_cast<int>(code_->size()); }
  void Patch(int at, int target) { (*code_)[at].a = target; }

  // ---- 顶层 ----
  void ParseGlobalDecl() {
    const bool is_int = Check(Tk::KwInt);
    Expect(is_int ? Tk::KwInt : Tk::KwFloat, "type");
    const Token& id = Advance();
    if (id.k != Tk::Id) throw CompileError{id.line, "expected identifier"};
    const std::string gname = id.text;
    const int slot0 = prog_.gmem_slots;
    bool is_array = false;
    int asize = 1;
    if (Match(Tk::LBracket)) {
      const Token& n = Advance();
      if (n.k != Tk::Num) throw CompileError{n.line, "array size must be a constant"};
      is_array = true;
      asize = static_cast<int>(n.num);
      if (asize <= 0) throw CompileError{n.line, "array size must be > 0"};
      prog_.gmem_slots += asize;
      Expect(Tk::RBracket, "]");
    } else {
      prog_.gmem_slots += 1;
    }
    Sym sym; sym.slot = slot0; sym.size = asize; sym.is_local = false; sym.is_array = is_array;
    globals_[gname] = sym;
    Global rec; rec.name = gname; rec.is_array = is_array; rec.size = asize; rec.slot = slot0;
    prog_.globals.push_back(rec);
    // 标量初值 → 放到 pre_init（在 init() 体之前执行）；数组初值暂不支持
    if (Match(Tk::Assign)) {
      if (is_array) throw CompileError{id.line, "array initializer not supported"};
      NodePtr init = ParseExpr();
      code_ = &pre_init_;
      GenValue(*init);
      Emit(Op::SetG, slot0);
    }
    ExpectSemi();
  }

  void ParseTopFunction() {
    // 返回类型
    if (Check(Tk::KwVoid) || Check(Tk::KwFloat) || Check(Tk::KwInt)) {
      Advance();
    } else {
      throw CompileError{Peek().line, "expected return type"};
    }
    const Token& name = Advance();
    if (name.k != Tk::Id) throw CompileError{name.line, "expected function name"};
    const bool is_entry =
        (name.text == "init" || name.text == "sample" || name.text == "block");
    // 参数列表
    std::vector<std::string> params;
    Expect(Tk::LParen, "(");
    if (!Check(Tk::RParen)) {
      do {
        if (!(Check(Tk::KwVoid) || Check(Tk::KwFloat) || Check(Tk::KwInt))) {
          throw CompileError{Peek().line, "expected parameter type"};
        }
        Advance();
        const Token& pn = Advance();
        if (pn.k != Tk::Id) throw CompileError{pn.line, "expected parameter name"};
        params.push_back(pn.text);
      } while (Match(Tk::Comma));
    }
    Expect(Tk::RParen, ")");

    if (is_entry) {
      if (!params.empty()) {
        throw CompileError{name.line, "init/sample/block take no parameters"};
      }
      locals_.clear();
      local_count_ = 0;
      if (name.text == "init") {
        code_ = &init_body_;
      } else if (name.text == "block") {
        code_ = &block_body_;
      } else {
        code_ = &entry_code_;
      }
      NodePtr body = ParseBlock();
      GenStmt(*body);
      Emit(Op::Ret);
      if (name.text == "init") {
        prog_.init_local_count = local_count_;
      } else if (name.text == "block") {
        prog_.block_local_count = local_count_;
      } else {
        prog_.sample_local_count = local_count_;
      }
      return;
    }

    RegisterUserFunction(name.text, params, {}, name.line);
  }

  // 登记并编译用户函数（C 风格花括号体 / EEL 括号体）
  void RegisterUserFunction(const std::string& fname, const std::vector<std::string>& params,
                            const std::vector<std::string>& extra_locals, int line) {
    if (params.size() > 8) throw CompileError{line, "too many parameters (max 8)"};
    Func f;
    f.name = fname;
    f.param_count = static_cast<int>(params.size());
    f.code_offset = static_cast<int>(func_code_.size());
    const int fid = static_cast<int>(funcs_.size());
    funcs_.push_back(f);
    func_index_[fname] = fid;

    auto saved_code = code_;
    auto saved_locals = locals_;
    const int saved_local_count = local_count_;
    code_ = &func_code_;
    locals_.clear();
    local_count_ = 0;
    for (const auto& p : params) {
      Sym s; s.slot = local_count_++; s.size = 1; s.is_local = true; s.is_array = false;
      locals_[p] = s;
    }
    for (const auto& l : extra_locals) {
      Sym s; s.slot = local_count_++; s.size = 1; s.is_local = true; s.is_array = false;
      locals_[l] = s;
    }
    NodePtr body = Check(Tk::LBrace) ? ParseBlock() : ParseParenBlock();
    // EEL 语义：函数体最后一个表达式即返回值 → 转成 return
    if (eel_ && !body->kids.empty() && body->kids.back()->t == NT::Expr &&
        !body->kids.back()->kids.empty()) {
      body->kids.back()->t = NT::Return;
    }
    GenStmt(*body);
    Emit(Op::Const, 0, 0.0);  // 无显式 return 时返回 0
    Emit(Op::Ret);
    funcs_[fid].local_count = local_count_;
    code_ = saved_code;
    locals_ = std::move(saved_locals);
    local_count_ = saved_local_count;
  }

  // EEL2: function name(a, b) local(t) ( ... )
  void ParseEelFunction() {
    Expect(Tk::KwFunction, "function");
    const Token& name = Advance();
    if (name.k != Tk::Id) throw CompileError{name.line, "expected function name"};
    std::vector<std::string> params;
    Expect(Tk::LParen, "(");
    if (!Check(Tk::RParen)) {
      do {
        const Token& pn = Advance();
        if (pn.k != Tk::Id) throw CompileError{pn.line, "expected parameter name"};
        params.push_back(pn.text);
      } while (Match(Tk::Comma));
    }
    Expect(Tk::RParen, ")");
    std::vector<std::string> extra;
    if (Check(Tk::Id) && Peek().text == "local") {
      Advance();
      Expect(Tk::LParen, "(");
      if (!Check(Tk::RParen)) {
        do {
          const Token& ln = Advance();
          if (ln.k == Tk::Id) extra.push_back(ln.text);
        } while (Match(Tk::Comma));
      }
      Expect(Tk::RParen, ")");
    }
    RegisterUserFunction(name.text, params, extra, name.line);
    Match(Tk::Semi);  // EEL 函数定义常以 `);` 结尾
  }

  // ---- 语句 ----
  NodePtr ParseBlock() {
    auto n = std::make_unique<Node>();
    n->t = NT::Block; n->line = Peek().line;
    Expect(Tk::LBrace, "{");
    while (!Check(Tk::RBrace) && !Check(Tk::End)) {
      n->kids.push_back(ParseStatement());
    }
    Expect(Tk::RBrace, "}");
    return n;
  }

  // EEL2 风格块：用 ( ... ) 而非 { ... }
  NodePtr ParseParenBlock() {
    auto n = std::make_unique<Node>();
    n->t = NT::Block;
    n->line = Peek().line;
    Expect(Tk::LParen, "(");
    while (!Check(Tk::RParen) && !Check(Tk::End)) n->kids.push_back(ParseStatement());
    Expect(Tk::RParen, ")");
    return n;
  }

  // EEL 的括号块里语句常省略分号：遇到 ')' 或末尾时允许缺省
  void ExpectSemi() {
    if (eel_ && (Check(Tk::RParen) || Check(Tk::End))) return;
    Expect(Tk::Semi, ";");
  }

  bool AtDeclStart() const { return Check(Tk::KwFloat) || Check(Tk::KwInt); }

  NodePtr ParseStatement() {
    if (Check(Tk::LBrace)) return ParseBlock();
    if (eel_ && Check(Tk::LParen)) return ParseParenBlock();
    if (AtDeclStart()) return ParseLocalDecl();
    if (Match(Tk::KwIf)) return ParseIf();
    if (Match(Tk::KwWhile)) return ParseWhile();
    if (Match(Tk::KwFor)) return ParseFor();
    if (Match(Tk::KwBreak)) {
      auto n = std::make_unique<Node>(); n->t = NT::Break; 
      ExpectSemi(); return n;
    }
    if (Match(Tk::KwContinue)) {
      auto n = std::make_unique<Node>(); n->t = NT::Continue;
      ExpectSemi(); return n;
    }
    if (Match(Tk::KwReturn)) {
      auto n = std::make_unique<Node>(); n->t = NT::Return;
      if (!Check(Tk::Semi)) n->kids.push_back(ParseExpr());
      ExpectSemi(); return n;
    }
    return ParseSimpleStatement();
  }

  NodePtr ParseLocalDecl() {
    const bool is_int = Check(Tk::KwInt);
    Expect(is_int ? Tk::KwInt : Tk::KwFloat, "type");
    const Token& id = Advance();
    if (id.k != Tk::Id) throw CompileError{id.line, "expected identifier"};
    auto n = std::make_unique<Node>();
    n->t = NT::Decl; n->name = id.text; n->line = id.line;
    n->is_local = true;
    if (Match(Tk::LBracket)) {
      const Token& sz = Advance();
      if (sz.k != Tk::Num) throw CompileError{sz.line, "array size must be a constant"};
      n->is_array = true;
      n->size = static_cast<int>(sz.num);
      if (n->size <= 0) throw CompileError{sz.line, "array size must be > 0"};
      Expect(Tk::RBracket, "]");
      n->slot = local_count_;
      local_count_ += n->size;
      Sym s; s.slot = n->slot; s.size = n->size; s.is_local = true; s.is_array = true;
      locals_[id.text] = s;
      ExpectSemi();  // 局部数组不支持初始化
      return n;
    }
    n->slot = local_count_++;
    Sym s; s.slot = n->slot; s.size = 1; s.is_local = true; s.is_array = false;
    locals_[id.text] = s;
    if (Match(Tk::Assign)) n->kids.push_back(ParseExpr());
    ExpectSemi();
    return n;
  }

  // 解析「赋值或表达式」，不消费结尾分号（供 for 的 init/step 复用）
  NodePtr ParseAssignOrExpr() {
    const std::size_t save = pos_;
    if (Check(Tk::Id) && Peek(1).k != Tk::LParen) {
      const std::string nm = Peek().text;
      if (locals_.count(nm) || globals_.count(nm) || eel_) {
        auto target = ParseLValue();
        if (target && IsAssignOp(Peek().k)) {
          auto n = std::make_unique<Node>();
          n->t = NT::Assign; n->line = target->line;
          n->op = AssignOpText(Advance().k);
          n->kids.push_back(std::move(target));
          n->kids.push_back(ParseExpr());
          return n;
        }
        if (target && (Check(Tk::PlusPlus) || Check(Tk::MinusMinus))) {
          const bool inc = Check(Tk::PlusPlus);
          Advance();
          auto n = std::make_unique<Node>();
          n->t = NT::Assign; n->line = target->line;
          n->op = inc ? "+=" : "-=";
          n->kids.push_back(std::move(target));
          auto one = std::make_unique<Node>();
          one->t = NT::Num; one->num = 1.0; one->line = n->line;
          n->kids.push_back(std::move(one));
          return n;
        }
      }
      pos_ = save;  // 回滚
    }
    auto n = std::make_unique<Node>();
    n->t = NT::Expr; n->line = Peek().line;
    n->kids.push_back(ParseExpr());
    return n;
  }

  NodePtr ParseSimpleStatement() {
    auto n = ParseAssignOrExpr();
    ExpectSemi();
    return n;
  }

  static bool IsAssignOp(Tk k) {
    return k == Tk::Assign || k == Tk::PlusEq || k == Tk::MinusEq ||
           k == Tk::StarEq || k == Tk::SlashEq;
  }
  static std::string AssignOpText(Tk k) {
    switch (k) {
      case Tk::Assign: return "=";
      case Tk::PlusEq: return "+=";
      case Tk::MinusEq: return "-=";
      case Tk::StarEq: return "*=";
      case Tk::SlashEq: return "/=";
      default: return "=";
    }
  }

  NodePtr ParseLValue() {
    if (!Check(Tk::Id)) return nullptr;
    const Token& id = Advance();
    auto n = std::make_unique<Node>();
    n->line = id.line;
    if (Match(Tk::LBracket)) {
      auto var = Lookup(id.text, id.line);
      if (!var.is_array) throw CompileError{id.line, id.text + " is not an array"};
      n->t = NT::Index; n->slot = var.slot; n->size = var.size;
      n->is_local = var.is_local; n->name = id.text;
      n->kids.push_back(ParseExpr());
      Expect(Tk::RBracket, "]");
      return n;
    }
    Sym var = Lookup(id.text, id.line);
    n->t = NT::Var; n->name = id.text; n->slot = var.slot;
    n->is_local = var.is_local; n->is_array = var.is_array;
    return n;
  }

  Sym Lookup(const std::string& name, int line) {
    auto l = locals_.find(name);
    if (l != locals_.end()) return l->second;
    auto g = globals_.find(name);
    if (g != globals_.end()) return g->second;
    if (eel_) {  // .eel 子集：隐式全局，首次出现即分配 gmem 槽
      Sym s;
      s.slot = prog_.gmem_slots++;
      s.size = 1;
      s.is_local = false;
      s.is_array = false;
      globals_[name] = s;
      Global rec;
      rec.name = name;
      rec.is_array = false;
      rec.size = 1;
      rec.slot = s.slot;
      prog_.globals.push_back(rec);
      return s;
    }
    throw CompileError{line, "undefined variable '" + name + "'"};
  }

  NodePtr ParseIf() {
    auto n = std::make_unique<Node>();
    n->t = NT::If; n->line = Peek().line;
    Expect(Tk::LParen, "(");
    n->kids.push_back(ParseExpr());
    Expect(Tk::RParen, ")");
    n->kids.push_back(ParseStatement());
    if (Match(Tk::KwElse)) n->kids.push_back(ParseStatement());
    return n;
  }

  NodePtr ParseWhile() {
    auto n = std::make_unique<Node>();
    n->t = NT::While; n->line = Peek().line;
    Expect(Tk::LParen, "(");
    n->kids.push_back(ParseExpr());
    Expect(Tk::RParen, ")");
    n->kids.push_back(ParseStatement());
    return n;
  }

  NodePtr ParseFor() {
    auto n = std::make_unique<Node>();
    n->t = NT::For; n->line = Peek().line;
    Expect(Tk::LParen, "(");
    // init
    if (Match(Tk::Semi)) {
      n->kids.push_back(std::make_unique<Node>());  // Empty
    } else if (AtDeclStart()) {
      n->kids.push_back(ParseLocalDecl());           // 含 ';'
    } else {
      n->kids.push_back(ParseAssignOrExpr());
      ExpectSemi();
    }
    // cond
    if (Check(Tk::Semi)) {
      n->kids.push_back(std::make_unique<Node>());
    } else {
      n->kids.push_back(ParseExpr());
    }
    ExpectSemi();
    // step（赋值或表达式，不收分号）
    if (Check(Tk::RParen)) {
      n->kids.push_back(std::make_unique<Node>());
    } else {
      n->kids.push_back(ParseAssignOrExpr());
    }
    Expect(Tk::RParen, ")");
    n->kids.push_back(ParseStatement());
    return n;
  }

  // ---- 表达式 ----
  NodePtr ParseExpr() {
    auto c = ParseOr();
    if (Match(Tk::Question)) {
      auto n = std::make_unique<Node>();
      n->t = NT::Ternary;
      n->line = c->line;
      n->kids.push_back(std::move(c));
      n->kids.push_back(ParseExpr());
      Expect(Tk::Colon, ":");
      n->kids.push_back(ParseExpr());
      return n;
    }
    return c;
  }

  NodePtr ParseOr() {
    auto l = ParseAnd();
    while (Check(Tk::OrOr)) {
      Advance();
      auto n = std::make_unique<Node>(); n->t = NT::Bin; n->op = "||"; n->line = l->line;
      n->kids.push_back(std::move(l)); n->kids.push_back(ParseAnd());
      l = std::move(n);
    }
    return l;
  }
  NodePtr ParseAnd() {
    auto l = ParseEquality();
    while (Check(Tk::AndAnd)) {
      Advance();
      auto n = std::make_unique<Node>(); n->t = NT::Bin; n->op = "&&"; n->line = l->line;
      n->kids.push_back(std::move(l)); n->kids.push_back(ParseEquality());
      l = std::move(n);
    }
    return l;
  }
  NodePtr ParseEquality() {
    auto l = ParseRelational();
    while (Check(Tk::EqEq) || Check(Tk::NotEq)) {
      const Tk k = Advance().k;
      auto n = std::make_unique<Node>(); n->t = NT::Bin; n->op = (k == Tk::EqEq ? "==" : "!=");
      n->line = l->line;
      n->kids.push_back(std::move(l)); n->kids.push_back(ParseRelational());
      l = std::move(n);
    }
    return l;
  }
  NodePtr ParseRelational() {
    auto l = ParseAdditive();
    while (Check(Tk::Lt) || Check(Tk::Le) || Check(Tk::Gt) || Check(Tk::Ge)) {
      const Tk k = Advance().k;
      auto n = std::make_unique<Node>(); n->t = NT::Bin;
      n->op = (k == Tk::Lt ? "<" : k == Tk::Le ? "<=" : k == Tk::Gt ? ">" : ">=");
      n->line = l->line;
      n->kids.push_back(std::move(l)); n->kids.push_back(ParseAdditive());
      l = std::move(n);
    }
    return l;
  }
  NodePtr ParseAdditive() {
    auto l = ParseMul();
    while (Check(Tk::Plus) || Check(Tk::Minus)) {
      const Tk k = Advance().k;
      auto n = std::make_unique<Node>(); n->t = NT::Bin; n->op = (k == Tk::Plus ? "+" : "-");
      n->line = l->line;
      n->kids.push_back(std::move(l)); n->kids.push_back(ParseMul());
      l = std::move(n);
    }
    return l;
  }
  NodePtr ParseMul() {
    auto l = ParseUnary();
    while (Check(Tk::Star) || Check(Tk::Slash) || Check(Tk::Percent)) {
      const Tk k = Advance().k;
      auto n = std::make_unique<Node>(); n->t = NT::Bin;
      n->op = (k == Tk::Star ? "*" : k == Tk::Slash ? "/" : "%");
      n->line = l->line;
      n->kids.push_back(std::move(l)); n->kids.push_back(ParseUnary());
      l = std::move(n);
    }
    return l;
  }
  NodePtr ParseUnary() {
    if (Check(Tk::Minus)) {
      Advance();
      auto n = std::make_unique<Node>(); n->t = NT::Un; n->op = "-";
      n->kids.push_back(ParseUnary());
      return n;
    }
    if (Check(Tk::Not)) {
      Advance();
      auto n = std::make_unique<Node>(); n->t = NT::Un; n->op = "!";
      n->kids.push_back(ParseUnary());
      return n;
    }
    return ParsePostfix();
  }
  NodePtr ParsePostfix() {
    auto n = ParsePrimary();
    return n;
  }
  NodePtr ParsePrimary() {
    if (Check(Tk::Num)) {
      auto n = std::make_unique<Node>(); n->t = NT::Num; n->num = Advance().num;
      n->line = Peek().line; return n;
    }
    if (Check(Tk::LParen)) {
      Advance();
      auto n = ParseExpr();
      Expect(Tk::RParen, ")");
      return n;
    }
    if (Check(Tk::Id)) {
      const Token& id = Advance();
      if (Match(Tk::LParen)) {
        auto n = std::make_unique<Node>(); n->t = NT::Call; n->op = id.text; n->line = id.line;
        auto fit = func_index_.find(id.text);
        if (fit != func_index_.end()) n->func_id = fit->second;
        if (!Check(Tk::RParen)) {
          n->kids.push_back(ParseExpr());
          while (Match(Tk::Comma)) n->kids.push_back(ParseExpr());
        }
        Expect(Tk::RParen, ")");
        if (n->func_id >= 0 &&
            static_cast<int>(n->kids.size()) !=
                funcs_[static_cast<std::size_t>(n->func_id)].param_count) {
          throw CompileError{id.line, "wrong number of arguments to '" + id.text + "'"};
        }
        return n;
      }
      if (Match(Tk::LBracket)) {
        Sym var = Lookup(id.text, id.line);
        if (!var.is_array) throw CompileError{id.line, id.text + " is not an array"};
        auto n = std::make_unique<Node>(); n->t = NT::Index;
        n->name = id.text; n->slot = var.slot; n->size = var.size;
        n->is_local = var.is_local; n->line = id.line;
        n->kids.push_back(ParseExpr());
        Expect(Tk::RBracket, "]");
        return n;
      }
      Sym var = Lookup(id.text, id.line);
      auto n = std::make_unique<Node>(); n->t = NT::Var;
      n->name = id.text; n->slot = var.slot; n->is_local = var.is_local;
      n->is_array = var.is_array; n->line = id.line;
      if (var.is_array) throw CompileError{id.line, "array '" + id.text + "' needs an index"};
      return n;
    }
    throw CompileError{Peek().line, "unexpected token in expression"};
  }

  // ---- 字节码生成 ----
  void GenValue(const Node& n) {
    switch (n.t) {
      case NT::Num: Emit(Op::Const, 0, n.num); break;
      case NT::Var:
        Emit(n.is_local ? Op::GetL : Op::GetG, n.slot);
        break;
      case NT::Index:
        GenValue(*n.kids[0]);
        Emit(n.is_local ? Op::LoadLArr : Op::LoadArr, n.slot,
             static_cast<double>(n.size));
        break;
      case NT::Bin: GenBin(n); break;
      case NT::Un:
        GenValue(*n.kids[0]);
        Emit(n.op == "-" ? Op::Neg : Op::Not);
        break;
      case NT::Call: {
        for (const auto& k : n.kids) GenValue(*k);
        if (n.func_id >= 0) {
          Emit(Op::CallUser, n.func_id, static_cast<double>(n.kids.size()));
        } else {
          const int bid = BuiltinId(n.op, n.line);
          const int want = BuiltinArity(bid);
          if (static_cast<int>(n.kids.size()) != want) {
            throw CompileError{n.line, "wrong number of arguments to '" + n.op +
                                           "' (expected " + std::to_string(want) + ")"};
          }
          Emit(Op::Call, bid, static_cast<double>(n.kids.size()));
        }
        break;
      }
      case NT::Ternary: {
        GenValue(*n.kids[0]);
        const int jz_at = static_cast<int>(code_->size());
        Emit(Op::Jz, 0, 0.0);
        GenValue(*n.kids[1]);
        const int jmp_at = static_cast<int>(code_->size());
        Emit(Op::Jmp, 0, 0.0);
        const int else_at = static_cast<int>(code_->size());
        GenValue(*n.kids[2]);
        const int end_at = static_cast<int>(code_->size());
        (*code_)[static_cast<std::size_t>(jz_at)].a = else_at;
        (*code_)[static_cast<std::size_t>(jmp_at)].a = end_at;
        break;
      }
      case NT::Assign: GenAssign(n); break;  // 表达式内赋值（本语言仅语句级，容错处理）
      default: Emit(Op::Const, 0, 0.0); break;
    }
  }

  void GenBin(const Node& n) {
    // 短路 && / ||
    if (n.op == "&&" || n.op == "||") {
      GenValue(*n.kids[0]);
      Emit(Op::Dup);
      const int j = Here();
      Emit(n.op == "&&" ? Op::Jz : Op::Jnz, 0);
      Emit(Op::Pop);
      GenValue(*n.kids[1]);
      Patch(j, Here());
      return;
    }
    GenValue(*n.kids[0]);
    GenValue(*n.kids[1]);
    const std::string& o = n.op;
    if (o == "+") Emit(Op::Add);
    else if (o == "-") Emit(Op::Sub);
    else if (o == "*") Emit(Op::Mul);
    else if (o == "/") Emit(Op::Div);
    else if (o == "%") Emit(Op::Mod);
    else if (o == "==") Emit(Op::CmpEq);
    else if (o == "!=") Emit(Op::CmpNe);
    else if (o == "<") Emit(Op::CmpLt);
    else if (o == "<=") Emit(Op::CmpLe);
    else if (o == ">") Emit(Op::CmpGt);
    else if (o == ">=") Emit(Op::CmpGe);
  }

  void GenAssign(const Node& n) {
    const Node& target = *n.kids[0];
    const Node& value = *n.kids[1];
    if (target.t == NT::Var) {
      if (n.op == "=") { GenValue(value); Emit(target.is_local ? Op::SetL : Op::SetG, target.slot); }
      else {
        Emit(target.is_local ? Op::GetL : Op::GetG, target.slot);
        GenValue(value);
        Emit(BinOpForAssign(n.op));
        Emit(target.is_local ? Op::SetL : Op::SetG, target.slot);
      }
    } else if (target.t == NT::Index) {
      const Op st = target.is_local ? Op::StoreLArr : Op::StoreArr;
      const Op ld = target.is_local ? Op::LoadLArr : Op::LoadArr;
      GenValue(*target.kids[0]);  // idx
      if (n.op == "=") {
        GenValue(value);
        Emit(st, target.slot, static_cast<double>(target.size));
      } else {
        Emit(Op::Dup);
        Emit(ld, target.slot, static_cast<double>(target.size));
        GenValue(value);
        Emit(BinOpForAssign(n.op));
        Emit(st, target.slot, static_cast<double>(target.size));
      }
    }
  }

  static Op BinOpForAssign(const std::string& o) {
    if (o == "+=") return Op::Add;
    if (o == "-=") return Op::Sub;
    if (o == "*=") return Op::Mul;
    if (o == "/=") return Op::Div;
    return Op::Add;
  }

  void GenStmt(const Node& n) {
    switch (n.t) {
      case NT::Block: for (const auto& k : n.kids) GenStmt(*k); break;
      case NT::Decl:
        if (n.is_array) break;  // 不支持局部数组
        if (!n.kids.empty()) { GenValue(*n.kids[0]); Emit(Op::SetL, n.slot); }
        break;
      case NT::Assign: GenAssign(n); break;
      case NT::Expr: GenValue(*n.kids[0]); Emit(Op::Pop); break;
      case NT::If: {
        GenValue(*n.kids[0]);
        const int j = Here(); Emit(Op::Jz, 0);
        GenStmt(*n.kids[1]);
        if (n.kids.size() > 2) {
          const int j2 = Here(); Emit(Op::Jmp, 0);
          Patch(j, Here());
          GenStmt(*n.kids[2]);
          Patch(j2, Here());
        } else {
          Patch(j, Here());
        }
        break;
      }
      case NT::While: {
        const int start = Here();
        GenValue(*n.kids[0]);
        const int j = Here(); Emit(Op::Jz, 0);
        LoopCtx ctx; ctx.cont_target = start; loops_.push_back(std::move(ctx));
        GenStmt(*n.kids[1]);
        Emit(Op::Jmp, start);
        const int end = Here();
        Patch(j, end);
        LoopCtx done = std::move(loops_.back()); loops_.pop_back();
        for (int b : done.breaks) Patch(b, end);
        break;
      }
      case NT::For: {
        GenStmt(*n.kids[0]);  // init
        const int start = Here();
        // cond（空=恒真）
        int j = -1;
        if (!(n.kids[1]->t == NT::Empty)) {
          GenValue(*n.kids[1]);
          j = Here(); Emit(Op::Jz, 0);
        }
        LoopCtx ctx;
        loops_.push_back(std::move(ctx));
        GenStmt(*n.kids[3]);  // body
        const int step = Here();
        loops_.back().cont_target = step;
        GenStmt(*n.kids[2]);  // step
        Emit(Op::Jmp, start);
        const int end = Here();
        if (j >= 0) Patch(j, end);
        LoopCtx done = std::move(loops_.back()); loops_.pop_back();
        for (int b : done.breaks) Patch(b, end);
        for (int c : done.continues) Patch(c, step);
        break;
      }
      case NT::Return:
        if (!n.kids.empty()) {
          GenValue(*n.kids[0]);
        } else {
          Emit(Op::Const, 0, 0.0);
        }
        Emit(Op::Ret);
        break;
      case NT::Break: {
        if (loops_.empty()) throw CompileError{n.line, "break outside loop"};
        const int j = Here(); Emit(Op::Jmp, 0); loops_.back().breaks.push_back(j);
        break;
      }
      case NT::Continue: {
        if (loops_.empty()) throw CompileError{n.line, "continue outside loop"};
        const int j = Here(); Emit(Op::Jmp, 0); loops_.back().continues.push_back(j);
        break;
      }
      default: break;
    }
  }

  uint8_t BuiltinId(const std::string& name, int line) const {
    static const std::unordered_map<std::string, uint8_t> kMap = {
      {"sin",0},{"cos",1},{"tan",2},{"asin",3},{"acos",4},{"atan",5},{"atan2",6},
      {"exp",7},{"log",8},{"log10",9},{"pow",10},{"sqrt",11},{"abs",12},{"floor",13},
      {"ceil",14},{"round",15},{"min",16},{"max",17},{"tanh",18},{"fmod",19},
      {"clamp",20},{"sign",21},{"saturate",22},
      // 内建 DSP 助手（状态由 VM 持有，见 Vm::BuiltinDsp）
      {"bq_lp",23},{"bq_hp",24},{"bq_peak",25},
      {"dl_write",26},{"dl_read",27},{"dl_clear",28},
      // 块处理
      {"blk_size",29},{"blk_get",30},{"blk_set",31},
      // FFT（实数输入，复数频谱）
      {"fft_size",32},{"fft_load",33},{"fft_forward",34},
      {"fft_re",35},{"fft_im",36},{"fft_set",37},{"fft_inverse",38},{"fft_out",39},
      // 卷积（时域 IR，≤1024 taps）
      {"conv_setup",40},{"conv_load",41},{"conv_run",42},
      // 分区 FFT 卷积（长 IR）
      {"convfft_setup",43},{"convfft_load",44},{"convfft_run",45},
    };
    auto it = kMap.find(name);
    if (it == kMap.end()) throw CompileError{line, "unknown function '" + name + "'"};
    return it->second;
  }
};

// ---------------------------------------------------------------------------
// 预处理：解析脚本头部注释里的控件声明
//   // sliderN: min, max                    连续值滑块
//   // toggleN: 显示名                      开关（0/1，持久）
//   // selectN: 显示名 = 选项1|选项2|...      下拉选择（0..N-1，持久）
//   // buttonN: 显示名                      按钮（按下后仅一个样本为 1）
// ---------------------------------------------------------------------------
inline void ParseControls(const std::string& src, Program& out) {
  for (int i = 0; i < 8; ++i) {
    out.slider_min[static_cast<std::size_t>(i)] = 0.0f;
    out.slider_max[static_cast<std::size_t>(i)] = 1.0f;
  }
  auto trim = [](const std::string& s) {
    const std::size_t b = s.find_first_not_of(" \t\r");
    if (b == std::string::npos) return std::string();
    const std::size_t e = s.find_last_not_of(" \t\r");
    return s.substr(b, e - b + 1);
  };
  std::size_t pos = 0;
  while (pos <= src.size()) {
    const std::size_t nl = src.find('\n', pos);
    const std::size_t end = (nl == std::string::npos) ? src.size() : nl;
    const std::string line = src.substr(pos, end - pos);
    pos = (nl == std::string::npos) ? src.size() + 1 : nl + 1;
    const std::size_t b = line.find_first_not_of(" \t\r");
    if (b == std::string::npos) continue;
    if (line.compare(b, 2, "//") != 0) continue;
    const std::size_t c = line.find_first_not_of(" \t", b + 2);
    if (c == std::string::npos) continue;
    const std::string rest = line.substr(c);

    // sliderN: min, max
    if (rest.rfind("slider", 0) == 0) {
      const std::size_t d = rest.find_first_of("0123456789", 6);
      if (d == std::string::npos) continue;
      const int idx = rest[d] - '1';
      if (idx < 0 || idx > 7) continue;
      const std::size_t colon = rest.find(':', d);
      if (colon == std::string::npos) continue;
      std::string nums = rest.substr(colon + 1);
      for (char& ch : nums) {
        if (ch == ',') ch = ' ';
      }
      const char* p = nums.c_str();
      char* e = nullptr;
      const float lo = static_cast<float>(std::strtod(p, &e));
      if (e == p) continue;
      const float hi = static_cast<float>(std::strtod(e, &e));
      if (e == p || hi <= lo) continue;
      out.slider_min[static_cast<std::size_t>(idx)] = lo;
      out.slider_max[static_cast<std::size_t>(idx)] = hi;
      // 可选第三个数字 = 默认值
      while (*e == ' ' || *e == '\t') ++e;
      if (*e != '\0') {
        char* e2 = nullptr;
        const float def = static_cast<float>(std::strtod(e, &e2));
        if (e2 != e) {
          out.slider_default[static_cast<std::size_t>(idx)] = def;
          out.slider_has_default[static_cast<std::size_t>(idx)] = true;
        }
      }
      continue;
    }

    // toggleN / selectN / buttonN / listN
    CtrlType type;
    if (rest.rfind("toggle", 0) == 0) type = CtrlType::Toggle;
    else if (rest.rfind("select", 0) == 0) type = CtrlType::Select;
    else if (rest.rfind("button", 0) == 0) type = CtrlType::Button;
    else if (rest.rfind("list", 0) == 0) type = CtrlType::List;
    else continue;

    // 从 4 开始找编号（"list" 只有 4 个字母，"toggle"/"select"/"button" 同样安全）
    const std::size_t d = rest.find_first_of("0123456789", 4);
    if (d == std::string::npos) continue;
    const int idx = rest[d] - '1';
    if (idx < 0 || idx > 7) continue;
    const std::size_t colon = rest.find(':', d);
    if (colon == std::string::npos) continue;
    std::string body = trim(rest.substr(colon + 1));

    Control ctl;
    ctl.type = type;
    ctl.index = idx;

    // 可选默认值：..., default=1.5
    if (type != CtrlType::Button) {
      const std::size_t dp = body.find("default=");
      if (dp != std::string::npos) {
        const std::string ds = trim(body.substr(dp + 8));
        if (!ds.empty()) {
          ctl.default_value = static_cast<float>(std::atof(ds.c_str()));
          ctl.has_default = true;
        }
        body = trim(body.substr(0, dp));
        if (!body.empty() && body.back() == ',') body.pop_back();
        body = trim(body);
      }
    }

    const char* kind = (type == CtrlType::Toggle) ? "toggle"
                     : (type == CtrlType::Select) ? "select"
                     : (type == CtrlType::Button) ? "button" : "list";
    ctl.name = std::string(kind) + std::to_string(idx + 1);

    if (type == CtrlType::Select || type == CtrlType::List) {
      std::string labels = body;
      const std::size_t eq = body.find('=');
      if (eq != std::string::npos) {
        ctl.label = trim(body.substr(0, eq));
        labels = body.substr(eq + 1);
      }
      std::size_t s = 0;
      while (s <= labels.size()) {
        const std::size_t bar = labels.find('|', s);
        const std::size_t e2 = (bar == std::string::npos) ? labels.size() : bar;
        const std::string opt = trim(labels.substr(s, e2 - s));
        if (!opt.empty()) ctl.options.push_back(opt);
        if (bar == std::string::npos) break;
        s = bar + 1;
      }
      if (ctl.options.empty()) continue;
      if (ctl.label.empty()) ctl.label = ctl.name;
      if (ctl.has_default) {
        const int n = static_cast<int>(ctl.options.size());
        int dv = static_cast<int>(ctl.default_value);
        if (dv < 0) dv = 0;
        if (type == CtrlType::Select) {
          if (dv >= n) dv = n - 1;
        } else {
          const int max_mask = (1 << n) - 1;
          if (dv > max_mask) dv = max_mask;
        }
        ctl.default_value = static_cast<float>(dv);
      }
    } else {
      ctl.label = body.empty() ? ctl.name : body;
      if (type == CtrlType::Toggle && ctl.has_default) {
        ctl.default_value = (ctl.default_value > 0.5f) ? 1.0f : 0.0f;
      }
    }
    out.controls.push_back(std::move(ctl));
  }
}

// ---------------------------------------------------------------------------
// .eel 子集：@init/@sample 分段 + sliderN 头 → 类 C 源码
// ---------------------------------------------------------------------------
inline std::string TrimCopy(const std::string& s) {
  const std::size_t b = s.find_first_not_of(" \t\r");
  if (b == std::string::npos) return std::string();
  const std::size_t e = s.find_last_not_of(" \t\r");
  return s.substr(b, e - b + 1);
}

inline bool LooksLikeEel(const std::string& src) {
  std::size_t pos = 0;
  while (pos <= src.size()) {
    const std::size_t nl = src.find('\n', pos);
    const std::size_t end = (nl == std::string::npos) ? src.size() : nl;
    const std::string t = TrimCopy(src.substr(pos, end - pos));
    pos = (nl == std::string::npos) ? src.size() + 1 : nl + 1;
    if (t.empty()) continue;
    if (t[0] == '@') return true;
    if (t.rfind("slider", 0) == 0 && t.find(':') != std::string::npos) return true;
  }
  return false;
}

inline std::string TranslateEel(const std::string& src, std::vector<int>* lmap) {
  std::vector<std::string> sliders;
  std::vector<std::string> pre_lines;  // 头部里的 function 定义（原样保留）
  std::vector<std::string> init_lines;
  std::vector<std::string> sample_lines;
  std::vector<int> pre_ln, init_ln, sample_ln;  // 对应原始行号（用于错误行映射）
  int section = 0;  // 0=头部 1=@init 2=@sample 3=其它（忽略）
  int func_depth = 0;  // function 定义内的括号深度
  int orig = 0;        // 当前原始行号（1 起）
  std::size_t pos = 0;
  while (pos <= src.size()) {
    const std::size_t nl = src.find('\n', pos);
    const std::size_t end = (nl == std::string::npos) ? src.size() : nl;
    const std::string line = src.substr(pos, end - pos);
    pos = (nl == std::string::npos) ? src.size() + 1 : nl + 1;
    ++orig;
    const std::string t = TrimCopy(line);
    if (!t.empty() && t[0] == '@') {
      if (t.rfind("@init", 0) == 0) section = 1;
      else if (t.rfind("@sample", 0) == 0) section = 2;
      else section = 3;
      continue;
    }
    if (section == 0) {
      auto paren_delta = [](const std::string& s) {
        int d = 0;
        for (char c : s) {
          if (c == '(') ++d;
          else if (c == ')') --d;
        }
        return d;
      };
      // function 定义：原样保留（含跨行括号体）
      if (func_depth > 0) {
        pre_lines.push_back(line);
        pre_ln.push_back(orig);
        func_depth += paren_delta(line);
        continue;
      }
      if (t.rfind("function", 0) == 0) {
        pre_lines.push_back(line);
        pre_ln.push_back(orig);
        func_depth += paren_delta(line);
        continue;
      }
      // sliderN:def<min<max>label  →  // sliderN: min, max, def
      if (t.rfind("slider", 0) == 0) {
        const std::size_t c = t.find(':');
        if (c != std::string::npos) {
          const std::string name = TrimCopy(t.substr(0, c));
          const std::string rest = t.substr(c + 1);
          const std::size_t lt1 = rest.find('<');
          if (lt1 != std::string::npos) {
            const std::string def = TrimCopy(rest.substr(0, lt1));
            const std::size_t lt2 = rest.find('<', lt1 + 1);
            if (lt2 != std::string::npos) {
              const std::string mn = TrimCopy(rest.substr(lt1 + 1, lt2 - lt1 - 1));
              const std::size_t gt = rest.find('>', lt2 + 1);
              if (gt != std::string::npos) {
                const std::string mx = TrimCopy(rest.substr(lt2 + 1, gt - lt2 - 1));
                if (!mn.empty() && !mx.empty()) {
                  sliders.push_back("// " + name + ": " + mn + ", " + mx + ", " +
                                    (def.empty() ? mn : def));
                }
              }
            }
          }
        }
      }
      continue;
    }
    if (section == 1) {
      init_lines.push_back(line);
      init_ln.push_back(orig);
    } else if (section == 2) {
      sample_lines.push_back(line);
      sample_ln.push_back(orig);
    }
  }
  std::string out;
  auto emit = [&](const std::string& s, int ln) {
    out += s;
    out += '\n';
    if (lmap != nullptr) lmap->push_back(ln);
  };
  for (const auto& s : sliders) emit(s, 0);          // 生成的注释行，无原始行号
  for (std::size_t i = 0; i < pre_lines.size(); ++i) {
    emit(pre_lines[i], (i < pre_ln.size()) ? pre_ln[i] : 0);
  }
  emit("void init() {", 0);
  for (std::size_t i = 0; i < init_lines.size(); ++i) {
    emit(init_lines[i], (i < init_ln.size()) ? init_ln[i] : 0);
  }
  emit("}", 0);
  emit("void sample() {", 0);
  for (std::size_t i = 0; i < sample_lines.size(); ++i) {
    emit(sample_lines[i], (i < sample_ln.size()) ? sample_ln[i] : 0);
  }
  emit("}", 0);
  return out;
}

// ---------------------------------------------------------------------------
// 编译入口
// ---------------------------------------------------------------------------
inline bool Compile(const std::string& source, Program& out, std::string& error) {
  std::vector<int> line_map;  // EEL：翻译后行号 → 原始行号（0 = 生成行）
  try {
    const bool eel = LooksLikeEel(source);
    const std::string src = eel ? TranslateEel(source, &line_map) : source;
    out = Program{};
    out.gmem_slots = kReservedSlots;
    ParseControls(src, out);
    Lexer lex(src);
    Parser parser(lex.Run(), out);
    if (eel) parser.SetEelMode(true);
    // 预注册 spl0/spl1/srate/slider1..8
    parser.Run();
    if (out.init_code.empty()) out.init_code.push_back(Instr{Op::Ret, 0, 0});
    if (out.sample_code.empty()) out.sample_code.push_back(Instr{Op::Ret, 0, 0});
    // 扫描字节码，标记脚本引用了哪些 slider（GetG/SetG 命中 slider 槽）
    for (const auto& in : out.init_code) {
      if ((in.op == Op::GetG || in.op == Op::SetG) && in.a >= kSlotSlider1 &&
          in.a < kSlotSlider1 + 8) {
        out.used_sliders[static_cast<std::size_t>(in.a - kSlotSlider1)] = true;
      }
    }
    for (const auto& in : out.sample_code) {
      if ((in.op == Op::GetG || in.op == Op::SetG) && in.a >= kSlotSlider1 &&
          in.a < kSlotSlider1 + 8) {
        out.used_sliders[static_cast<std::size_t>(in.a - kSlotSlider1)] = true;
      }
    }
    error.clear();
    return true;
  } catch (const CompileError& e) {
    int line = e.line;
    // EEL 子集：把翻译后的行号映射回用户编辑器里的原始行号
    if (!line_map.empty() && line >= 1 && line <= static_cast<int>(line_map.size())) {
      const int mapped = line_map[static_cast<std::size_t>(line - 1)];
      if (mapped > 0) line = mapped;
    }
    error = "line " + std::to_string(line) + ": " + e.msg;
    return false;
  } catch (const std::exception& e) {
    // 兜底：任何标准异常都不能穿透 JNI 边界（穿透即闪退）
    error = std::string("internal compiler error: ") + e.what();
    return false;
  } catch (...) {
    error = "internal compiler error";
    return false;
  }
}

// ---------------------------------------------------------------------------
// VM
// ---------------------------------------------------------------------------
inline double Builtin(int id, const double* a, int n) {
  switch (id) {
    case 0: return std::sin(a[0]);
    case 1: return std::cos(a[0]);
    case 2: return std::tan(a[0]);
    case 3: return std::asin(a[0]);
    case 4: return std::acos(a[0]);
    case 5: return std::atan(a[0]);
    case 6: return std::atan2(a[0], a[1]);
    case 7: return std::exp(a[0]);
    case 8: return std::log(a[0]);
    case 9: return std::log10(a[0]);
    case 10: return std::pow(a[0], a[1]);
    case 11: return std::sqrt(a[0]);
    case 12: return std::fabs(a[0]);
    case 13: return std::floor(a[0]);
    case 14: return std::ceil(a[0]);
    case 15: return std::round(a[0]);
    case 16: return (n >= 2 && a[0] < a[1]) ? a[0] : (n >= 2 ? a[1] : a[0]);
    case 17: return (n >= 2 && a[0] > a[1]) ? a[0] : (n >= 2 ? a[1] : a[0]);
    case 18: return std::tanh(a[0]);
    case 19: return std::fmod(a[0], a[1]);
    case 20: return (a[0] < a[1]) ? a[1] : (a[0] > a[2] ? a[2] : a[0]);
    case 21: return (a[0] > 0) ? 1.0 : (a[0] < 0 ? -1.0 : 0.0);
    case 22: return (a[0] < 0) ? 0.0 : (a[0] > 1 ? 1.0 : a[0]);
    default: return 0.0;
  }
}

class Vm {
 public:
  static constexpr int kMaxStack = 512;
  static constexpr int kMaxLocals = 512;
  static constexpr int kMaxCallDepth = 64;
  static constexpr int kInstrBudget = 200000;

  void Reset(const Program* p) {
    prog_ = p;
    gmem_.assign(static_cast<std::size_t>(p->gmem_slots > 0 ? p->gmem_slots : 1), 0.0f);
    locals_.assign(static_cast<std::size_t>(kMaxLocals), 0.0f);
    for (auto& b : bq_) b = Bq{};
    for (auto& d : delay_) d.clear();
    for (int i = 0; i < kDelayCount; ++i) delay_pos_[i] = 0;
    for (auto& c : conv_) c = Conv{};
    ResetPc();
  }

  float* gmem() { return gmem_.data(); }

  void SetSampleRate(float sr) { sample_rate_ = (sr > 0.0f) ? sr : 48000.0f; }

  void RunInit() {
    for (auto& v : locals_) v = 0.0f;
    frame_base_ = 0;
    frame_top_ = prog_->init_local_count;
    call_depth_ = 0;
    Exec(prog_->init_code);
  }

  void RunSample() {
    for (auto& v : locals_) v = 0.0f;
    frame_base_ = 0;
    frame_top_ = prog_->sample_local_count;
    call_depth_ = 0;
    Exec(prog_->sample_code);
  }

 private:
  const Program* prog_ = nullptr;
  std::vector<float> gmem_;
  std::vector<float> locals_;
  int frame_base_ = 0;   // 当前局部帧基址
  int frame_top_ = 0;    // 局部栈顶（下一个帧起始）
  int call_depth_ = 0;
  int ret_pc_[kMaxCallDepth] = {};
  int ret_frame_[kMaxCallDepth] = {};
  double st_[kMaxStack] = {};

  // ---- 内建 DSP 助手（biquad / 延迟线，状态由 VM 持有）----
  static constexpr int kBqCount = 4;
  static constexpr int kDelayCount = 4;
  static constexpr int kMaxDelayLen = 48000;  // 1s @48k

  struct Bq {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float s1 = 0.0f, s2 = 0.0f;
    float last_f = 0.0f, last_q = 0.0f, last_db = 0.0f;
    int type = -1;
  };

  Bq bq_[kBqCount];
  std::vector<float> delay_[kDelayCount];
  int delay_pos_[kDelayCount] = {};
  float sample_rate_ = 48000.0f;

  // 时域卷积器（2 个实例，IR ≤ 1024 taps，配合 block() 使用）
  struct Conv {
    std::vector<float> ir;
    std::vector<float> hist;
    int pos = 0;
  };
  Conv conv_[4];  // 2 个实例 × 2 声道（历史不可跨声道共用）

  // ---- 分区 FFT 卷积（长 IR，N=1024 / hop=512，上限 32768 taps）----
  static constexpr int kPcN = 1024;
  static constexpr int kPcP = 512;
  static constexpr int kPcMaxTaps = 32768;

  struct PConvCh {
    std::vector<float> hist;  // 上一步输入（P）
    std::vector<float> acc;   // 当前 hop 累积（P）
    int fill = 0;
    std::vector<float> out;   // 输出 FIFO
    std::size_t outPos = 0;
    std::vector<float> fdlR;  // 频域延迟线 parts*N
    std::vector<float> fdlI;
    int fdlPos = 0;
  };

  int pcTaps_ = 0;
  int pcParts_ = 0;
  bool pcReady_ = false;
  std::vector<float> pcIr_;    // 时域 IR
  std::vector<float> pcIrR_;   // 各分区频谱（parts*N）
  std::vector<float> pcIrI_;
  PConvCh pcCh_[2];

  void ResetPc() {
    pcTaps_ = 0;
    pcParts_ = 0;
    pcReady_ = false;
    pcIr_.clear();
    pcIrR_.clear();
    pcIrI_.clear();
    for (auto& c : pcCh_) c = PConvCh{};
  }

  static int NextPow2(int v) {
    int p = 2;
    while (p < v) p <<= 1;
    return p;
  }

  // 装载完成后再建分区频谱（避免每次 load 都重算）
  void BuildPcPartitions() {
    if (pcTaps_ <= 0) return;
    pcParts_ = (pcTaps_ + kPcP - 1) / kPcP;
    const int n = kPcN;
    pcIrR_.assign(static_cast<std::size_t>(pcParts_) * n, 0.0f);
    pcIrI_.assign(static_cast<std::size_t>(pcParts_) * n, 0.0f);
    std::vector<float> xr(static_cast<std::size_t>(n), 0.0f);
    std::vector<float> xi(static_cast<std::size_t>(n), 0.0f);
    for (int p = 0; p < pcParts_; ++p) {
      std::fill(xr.begin(), xr.end(), 0.0f);
      std::fill(xi.begin(), xi.end(), 0.0f);
      const int base = p * kPcP;
      const int len = std::min(kPcP, pcTaps_ - base);
      for (int i = 0; i < len; ++i) {
        xr[static_cast<std::size_t>(i)] = pcIr_[static_cast<std::size_t>(base + i)];
      }
      Fft(xr, xi, false);
      for (int i = 0; i < n; ++i) {
        pcIrR_[static_cast<std::size_t>(p) * n + i] = xr[static_cast<std::size_t>(i)];
        pcIrI_[static_cast<std::size_t>(p) * n + i] = xi[static_cast<std::size_t>(i)];
      }
    }
    for (auto& c : pcCh_) {
      c.hist.assign(static_cast<std::size_t>(kPcP), 0.0f);
      c.acc.assign(static_cast<std::size_t>(kPcP), 0.0f);
      c.fill = 0;
      c.out.clear();
      c.outPos = 0;
      c.fdlR.assign(static_cast<std::size_t>(pcParts_) * n, 0.0f);
      c.fdlI.assign(static_cast<std::size_t>(pcParts_) * n, 0.0f);
      c.fdlPos = 0;
    }
    pcReady_ = true;
  }

  // 处理一个 hop：把 P 个新样本推进频域延迟线并产出 P 个输出
  void ProcessPcHop(int ch) {
    PConvCh& c = pcCh_[(ch == 1) ? 1 : 0];
    const int n = kPcN;
    std::vector<float> xr(static_cast<std::size_t>(n), 0.0f);
    std::vector<float> xi(static_cast<std::size_t>(n), 0.0f);
    for (int i = 0; i < kPcP; ++i) {
      xr[static_cast<std::size_t>(i)] = c.hist[static_cast<std::size_t>(i)];
      xr[static_cast<std::size_t>(kPcP + i)] = c.acc[static_cast<std::size_t>(i)];
    }
    Fft(xr, xi, false);
    // 写入频域延迟线
    const int slot = c.fdlPos * n;
    for (int i = 0; i < n; ++i) {
      c.fdlR[static_cast<std::size_t>(slot + i)] = xr[static_cast<std::size_t>(i)];
      c.fdlI[static_cast<std::size_t>(slot + i)] = xi[static_cast<std::size_t>(i)];
    }
    // 累加各分区乘积
    std::vector<float> yr(static_cast<std::size_t>(n), 0.0f);
    std::vector<float> yi(static_cast<std::size_t>(n), 0.0f);
    for (int p = 0; p < pcParts_; ++p) {
      const int idx = (c.fdlPos - p + pcParts_) % pcParts_;
      const int a = idx * n;
      const int b = p * n;
      for (int i = 0; i < n; ++i) {
        const float ar = c.fdlR[static_cast<std::size_t>(a + i)];
        const float ai = c.fdlI[static_cast<std::size_t>(a + i)];
        const float br = pcIrR_[static_cast<std::size_t>(b + i)];
        const float bi = pcIrI_[static_cast<std::size_t>(b + i)];
        yr[static_cast<std::size_t>(i)] += ar * br - ai * bi;
        yi[static_cast<std::size_t>(i)] += ar * bi + ai * br;
      }
    }
    Fft(yr, yi, true);
    // 叠接相加：后 P 点为有效输出
    for (int i = 0; i < kPcP; ++i) {
      c.out.push_back(yr[static_cast<std::size_t>(kPcP + i)]);
    }
    // 滚动状态
    c.hist = c.acc;
    std::fill(c.acc.begin(), c.acc.end(), 0.0f);
    c.fill = 0;
    c.fdlPos = (c.fdlPos + 1) % pcParts_;
  }

  void RunPcBlock(int ch) {
    if (pcTaps_ <= 0) return;
    if (!pcReady_) BuildPcPartitions();
    PConvCh& c = pcCh_[(ch == 1) ? 1 : 0];
    for (int i = 0; i < blk_frames_; ++i) {
      const float x = BlockGet(ch, i);  // 先取输入（随后会被输出覆盖）
      const float y = (c.outPos < c.out.size()) ? c.out[c.outPos++] : 0.0f;
      BlockSet(ch, i, y);
      if (c.outPos == c.out.size()) {
        c.out.clear();
        c.outPos = 0;
      }
      c.acc[static_cast<std::size_t>(c.fill++)] = x;
      if (c.fill == kPcP) ProcessPcHop((ch == 1) ? 1 : 0);
    }
  }

  static int ClampSlot(int v, int n) { return (v < 0) ? 0 : (v >= n ? n - 1 : v); }

  void EnsureDelay(int i) {
    if (delay_[i].empty()) delay_[i].assign(static_cast<std::size_t>(kMaxDelayLen), 0.0f);
  }

  void SetBqCoeffs(Bq& s, int type, float f, float q, float db) {
    const float sr = (sample_rate_ > 0.0f) ? sample_rate_ : 48000.0f;
    float ff = f;
    if (ff < 10.0f) ff = 10.0f;
    if (ff > sr * 0.45f) ff = sr * 0.45f;
    float qq = q;
    if (qq < 0.1f) qq = 0.1f;
    const float pi = 3.14159265358979f;
    const float w0 = 2.0f * pi * ff / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float alpha = sw / (2.0f * qq);
    float b0, b1, b2, a0, a1, a2;
    if (type == 0) {  // low-pass
      b0 = (1.0f - cw) * 0.5f;
      b1 = 1.0f - cw;
      b2 = b0;
      a0 = 1.0f + alpha;
      a1 = -2.0f * cw;
      a2 = 1.0f - alpha;
    } else if (type == 1) {  // high-pass
      b0 = (1.0f + cw) * 0.5f;
      b1 = -(1.0f + cw);
      b2 = b0;
      a0 = 1.0f + alpha;
      a1 = -2.0f * cw;
      a2 = 1.0f - alpha;
    } else {  // peaking EQ
      const float amp = std::pow(10.0f, db / 40.0f);
      b0 = 1.0f + alpha * amp;
      b1 = -2.0f * cw;
      b2 = 1.0f - alpha * amp;
      a0 = 1.0f + alpha / amp;
      a1 = -2.0f * cw;
      a2 = 1.0f - alpha / amp;
    }
    s.b0 = b0 / a0;
    s.b1 = b1 / a0;
    s.b2 = b2 / a0;
    s.a1 = a1 / a0;
    s.a2 = a2 / a0;
  }

  double BuiltinDsp(int id, const double* a, int n) {
    // ---- 块处理 ----
    if (id == 29) return static_cast<double>(blk_frames_);
    if (id == 30) {  // blk_get(ch, i)
      if (n < 2) return 0.0;
      return static_cast<double>(
          BlockGet(static_cast<int>(a[0]), static_cast<int>(a[1])));
    }
    if (id == 31) {  // blk_set(ch, i, v)
      if (n < 3) return 0.0;
      BlockSet(static_cast<int>(a[0]), static_cast<int>(a[1]), static_cast<float>(a[2]));
      return a[2];
    }
    // ---- FFT ----
    if (id == 32) {  // fft_size(n) → 实际采用的 2 的幂
      int sz = static_cast<int>(a[0]);
      if (sz < 16) sz = 16;
      if (sz > kFftMax) sz = kFftMax;
      int p = 16;
      while (p < sz) p <<= 1;
      fft_n_ = p;
      fft_re_.assign(static_cast<std::size_t>(p), 0.0f);
      fft_im_.assign(static_cast<std::size_t>(p), 0.0f);
      return static_cast<double>(p);
    }
    if (id == 33) {  // fft_load(i, v)
      if (n < 2) return 0.0;
      const int i = static_cast<int>(a[0]);
      if (i < 0 || i >= fft_n_) return 0.0;
      fft_re_[static_cast<std::size_t>(i)] = static_cast<float>(a[1]);
      fft_im_[static_cast<std::size_t>(i)] = 0.0f;
      return a[1];
    }
    if (id == 34) {  // fft_forward()
      if (fft_n_ > 0) Fft(fft_re_, fft_im_, false);
      return 0.0;
    }
    if (id == 35) {  // fft_re(i)
      const int i = static_cast<int>(a[0]);
      return (i >= 0 && i < fft_n_) ? static_cast<double>(fft_re_[static_cast<std::size_t>(i)])
                                    : 0.0;
    }
    if (id == 36) {  // fft_im(i)
      const int i = static_cast<int>(a[0]);
      return (i >= 0 && i < fft_n_) ? static_cast<double>(fft_im_[static_cast<std::size_t>(i)])
                                    : 0.0;
    }
    if (id == 37) {  // fft_set(i, re, im)
      if (n < 3) return 0.0;
      const int i = static_cast<int>(a[0]);
      if (i >= 0 && i < fft_n_) {
        fft_re_[static_cast<std::size_t>(i)] = static_cast<float>(a[1]);
        fft_im_[static_cast<std::size_t>(i)] = static_cast<float>(a[2]);
      }
      return 0.0;
    }
    if (id == 38) {  // fft_inverse()（含 1/N 归一化）
      if (fft_n_ > 0) Fft(fft_re_, fft_im_, true);
      return 0.0;
    }
    if (id == 39) {  // fft_out(i)（逆变换后的实部）
      const int i = static_cast<int>(a[0]);
      return (i >= 0 && i < fft_n_) ? static_cast<double>(fft_re_[static_cast<std::size_t>(i)])
                                    : 0.0;
    }
    // ---- 分区 FFT 卷积（长 IR）----
    if (id == 43) {  // convfft_setup(taps)
      int taps = static_cast<int>(a[0]);
      if (taps < 1) taps = 1;
      if (taps > kPcMaxTaps) taps = kPcMaxTaps;
      ResetPc();
      pcTaps_ = taps;
      pcIr_.assign(static_cast<std::size_t>(taps), 0.0f);
      return static_cast<double>(taps);
    }
    if (id == 44) {  // convfft_load(k, v)
      if (n < 2) return 0.0;
      const int k = static_cast<int>(a[0]);
      if (k >= 0 && k < pcTaps_) {
        pcIr_[static_cast<std::size_t>(k)] = static_cast<float>(a[1]);
      }
      pcReady_ = false;
      return a[1];
    }
    if (id == 45) {  // convfft_run(ch)
      RunPcBlock(static_cast<int>(a[0]));
      return 0.0;
    }
    // ---- 卷积（时域，IR ≤ 1024 taps）----
    if (id == 40) {  // conv_setup(id, taps)
      if (n < 2) return 0.0;
      const int ci = ClampSlot(static_cast<int>(a[0]), 2);
      int taps = static_cast<int>(a[1]);
      if (taps < 1) taps = 1;
      if (taps > 1024) taps = 1024;
      for (int c = 0; c < 2; ++c) {
        Conv& cv = conv_[ci * 2 + c];
        cv.ir.assign(static_cast<std::size_t>(taps), 0.0f);
        cv.hist.assign(static_cast<std::size_t>(taps), 0.0f);
        cv.pos = 0;
      }
      return static_cast<double>(taps);
    }
    if (id == 41) {  // conv_load(id, k, v)
      if (n < 3) return 0.0;
      const int ci = ClampSlot(static_cast<int>(a[0]), 2);
      const int k = static_cast<int>(a[1]);
      for (int c = 0; c < 2; ++c) {
        Conv& cv = conv_[ci * 2 + c];
        if (k >= 0 && k < static_cast<int>(cv.ir.size())) {
          cv.ir[static_cast<std::size_t>(k)] = static_cast<float>(a[2]);
        }
      }
      return a[2];
    }
    if (id == 42) {  // conv_run(id, ch)：卷积当前块并写回
      if (n < 2) return 0.0;
      const int ci = ClampSlot(static_cast<int>(a[0]), 2);
      const int ch = static_cast<int>(a[1]);
      Conv& cv = conv_[ci * 2 + (ch == 1 ? 1 : 0)];
      const int len = static_cast<int>(cv.ir.size());
      if (len <= 0) return 0.0;
      for (int i = 0; i < blk_frames_; ++i) {
        const float x = BlockGet(ch, i);
        cv.hist[static_cast<std::size_t>(cv.pos)] = x;
        double y = 0.0;
        int idx = cv.pos;
        for (int k = 0; k < len; ++k) {
          y += static_cast<double>(cv.ir[static_cast<std::size_t>(k)]) *
               cv.hist[static_cast<std::size_t>(idx)];
          idx = (idx == 0) ? (len - 1) : (idx - 1);
        }
        cv.pos = (cv.pos + 1) % len;
        BlockSet(ch, i, static_cast<float>(y));
      }
      return 0.0;
    }
    if (id == 26) {  // dl_write(id, x) → 返回 x
      if (n < 2) return 0.0;
      const int di = ClampSlot(static_cast<int>(a[0]), kDelayCount);
      EnsureDelay(di);
      delay_[di][static_cast<std::size_t>(delay_pos_[di])] = static_cast<float>(a[1]);
      delay_pos_[di] = (delay_pos_[di] + 1) % kMaxDelayLen;
      return a[1];
    }
    if (id == 27) {  // dl_read(id, samples)
      if (n < 2) return 0.0;
      const int di = ClampSlot(static_cast<int>(a[0]), kDelayCount);
      EnsureDelay(di);
      double d = a[1];
      if (d < 0.0) d = 0.0;
      if (d > static_cast<double>(kMaxDelayLen - 2)) d = static_cast<double>(kMaxDelayLen - 2);
      double idx = static_cast<double>(delay_pos_[di]) - 1.0 - d;
      while (idx < 0.0) idx += static_cast<double>(kMaxDelayLen);
      const int i0 = static_cast<int>(idx) % kMaxDelayLen;
      const int i1 = (i0 + 1) % kMaxDelayLen;
      const double frac = idx - std::floor(idx);
      return static_cast<double>(delay_[di][static_cast<std::size_t>(i0)]) * (1.0 - frac) +
             static_cast<double>(delay_[di][static_cast<std::size_t>(i1)]) * frac;
    }
    if (id == 28) {  // dl_clear(id)
      const int di = ClampSlot(static_cast<int>(n > 0 ? a[0] : 0.0), kDelayCount);
      EnsureDelay(di);
      for (auto& v : delay_[di]) v = 0.0f;
      delay_pos_[di] = 0;
      return 0.0;
    }
    // 23/24/25：biquad
    const int bi = ClampSlot(static_cast<int>(a[0]), kBqCount);
    const float f = static_cast<float>(n > 1 ? a[1] : 1000.0);
    const float q = static_cast<float>(n > 2 ? a[2] : 0.707f);
    int type = 0;
    float db = 0.0f;
    float x = 0.0f;
    if (id == 23) {
      type = 0;
      x = static_cast<float>(n > 3 ? a[3] : 0.0);
    } else if (id == 24) {
      type = 1;
      x = static_cast<float>(n > 3 ? a[3] : 0.0);
    } else {
      type = 2;
      db = static_cast<float>(n > 3 ? a[3] : 0.0);
      x = static_cast<float>(n > 4 ? a[4] : 0.0);
    }
    Bq& s = bq_[static_cast<std::size_t>(bi)];
    if (s.type != type || s.last_f != f || s.last_q != q || s.last_db != db) {
      SetBqCoeffs(s, type, f, q, db);
      s.type = type;
      s.last_f = f;
      s.last_q = q;
      s.last_db = db;
    }
    const float y = s.b0 * x + s.s1;
    s.s1 = s.b1 * x - s.a1 * y + s.s2;
    s.s2 = s.b2 * x - s.a2 * y;
    return static_cast<double>(y);
  }

 public:
  // ---- 块处理（block() 段）与 FFT ----
  static constexpr int kFftMax = 4096;
  int blk_frames_ = 0;
  std::vector<float> blk_l_, blk_r_;
  int fft_n_ = 0;
  std::vector<float> fft_re_, fft_im_;

  void SetBlock(int frames, const float* l, const float* r) {
    blk_frames_ = (frames > 0) ? frames : 0;
    blk_l_.assign(static_cast<std::size_t>(blk_frames_), 0.0f);
    blk_r_.assign(static_cast<std::size_t>(blk_frames_), 0.0f);
    for (int i = 0; i < blk_frames_; ++i) {
      blk_l_[static_cast<std::size_t>(i)] = (l != nullptr) ? l[i] : 0.0f;
      blk_r_[static_cast<std::size_t>(i)] = (r != nullptr) ? r[i] : 0.0f;
    }
  }

  int BlockFrames() const { return blk_frames_; }

  float BlockGet(int ch, int i) const {
    if (i < 0 || i >= blk_frames_) return 0.0f;
    const std::vector<float>& v = (ch == 1) ? blk_r_ : blk_l_;
    return v[static_cast<std::size_t>(i)];
  }

  void BlockSet(int ch, int i, float v) {
    if (i < 0 || i >= blk_frames_) return;
    std::vector<float>& d = (ch == 1) ? blk_r_ : blk_l_;
    d[static_cast<std::size_t>(i)] = v;
  }

  void CopyBlockOut(float* l, float* r) const {
    for (int i = 0; i < blk_frames_; ++i) {
      if (l != nullptr) l[i] = blk_l_[static_cast<std::size_t>(i)];
      if (r != nullptr) r[i] = blk_r_[static_cast<std::size_t>(i)];
    }
  }

  void RunBlock() {
    if (prog_ == nullptr || prog_->block_offset < 0) return;
    for (auto& v : locals_) v = 0.0f;
    frame_base_ = 0;
    frame_top_ = prog_->block_local_count;
    call_depth_ = 0;
    Exec(prog_->sample_code, prog_->block_offset);
  }

  // 迭代 radix-2 FFT（原地）；inverse 时归一化 1/N
  static void Fft(std::vector<float>& re, std::vector<float>& im, bool inverse) {
    const int n = static_cast<int>(re.size());
    if (n < 2 || (n & (n - 1)) != 0) return;
    for (int i = 1, j = 0; i < n; ++i) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        std::swap(re[static_cast<std::size_t>(i)], re[static_cast<std::size_t>(j)]);
        std::swap(im[static_cast<std::size_t>(i)], im[static_cast<std::size_t>(j)]);
      }
    }
    const double pi = 3.14159265358979323846;
    for (int len = 2; len <= n; len <<= 1) {
      const double ang = 2.0 * pi / static_cast<double>(len) * (inverse ? 1.0 : -1.0);
      const double wr = std::cos(ang);
      const double wi = std::sin(ang);
      for (int i = 0; i < n; i += len) {
        double cr = 1.0;
        double ci = 0.0;
        const int half = len >> 1;
        for (int k = 0; k < half; ++k) {
          const int i0 = i + k;
          const int i1 = i0 + half;
          const double ur = re[static_cast<std::size_t>(i0)];
          const double ui = im[static_cast<std::size_t>(i0)];
          const double vr = re[static_cast<std::size_t>(i1)] * cr -
                            im[static_cast<std::size_t>(i1)] * ci;
          const double vi = re[static_cast<std::size_t>(i1)] * ci +
                            im[static_cast<std::size_t>(i1)] * cr;
          re[static_cast<std::size_t>(i0)] = static_cast<float>(ur + vr);
          im[static_cast<std::size_t>(i0)] = static_cast<float>(ui + vi);
          re[static_cast<std::size_t>(i1)] = static_cast<float>(ur - vr);
          im[static_cast<std::size_t>(i1)] = static_cast<float>(ui - vi);
          const double ncr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = ncr;
        }
      }
    }
    if (inverse) {
      for (int i = 0; i < n; ++i) {
        re[static_cast<std::size_t>(i)] /= static_cast<float>(n);
        im[static_cast<std::size_t>(i)] /= static_cast<float>(n);
      }
    }
  }

 private:
  void Exec(const std::vector<Instr>& code, int start_pc = 0) {
    int pc = start_pc;
    int sp = 0;
    int budget = kInstrBudget;
    const int n = static_cast<int>(code.size());
    while (pc < n) {
      if (--budget < 0) return;  // 脚本死循环保护：丢弃当前样本
      const Instr& in = code[static_cast<std::size_t>(pc++)];
      switch (in.op) {
        case Op::Const: st_[sp++] = in.b; break;
        case Op::GetG: st_[sp++] = gmem_[static_cast<std::size_t>(in.a)]; break;
        case Op::SetG: gmem_[static_cast<std::size_t>(in.a)] = static_cast<float>(st_[--sp]); break;
        case Op::GetL:
          st_[sp++] = locals_[static_cast<std::size_t>(frame_base_ + in.a)];
          break;
        case Op::SetL:
          locals_[static_cast<std::size_t>(frame_base_ + in.a)] = static_cast<float>(st_[--sp]);
          break;
        case Op::LoadArr: {
          const int idx = static_cast<int>(st_[--sp]);
          const int sz = static_cast<int>(in.b);
          st_[sp++] = (idx >= 0 && idx < sz) ? gmem_[static_cast<std::size_t>(in.a + idx)] : 0.0;
          break;
        }
        case Op::StoreArr: {
          const double val = st_[--sp];
          const int idx = static_cast<int>(st_[--sp]);
          const int sz = static_cast<int>(in.b);
          if (idx >= 0 && idx < sz) gmem_[static_cast<std::size_t>(in.a + idx)] = static_cast<float>(val);
          break;
        }
        case Op::LoadLArr: {
          const int idx = static_cast<int>(st_[--sp]);
          const int sz = static_cast<int>(in.b);
          st_[sp++] = (idx >= 0 && idx < sz)
                          ? locals_[static_cast<std::size_t>(frame_base_ + in.a + idx)]
                          : 0.0;
          break;
        }
        case Op::StoreLArr: {
          const double val = st_[--sp];
          const int idx = static_cast<int>(st_[--sp]);
          const int sz = static_cast<int>(in.b);
          if (idx >= 0 && idx < sz) {
            locals_[static_cast<std::size_t>(frame_base_ + in.a + idx)] = static_cast<float>(val);
          }
          break;
        }
        case Op::Dup: st_[sp] = st_[sp - 1]; ++sp; break;
        case Op::Add: { double b = st_[--sp]; st_[sp - 1] += b; break; }
        case Op::Sub: { double b = st_[--sp]; st_[sp - 1] -= b; break; }
        case Op::Mul: { double b = st_[--sp]; st_[sp - 1] *= b; break; }
        case Op::Div: { double b = st_[--sp]; st_[sp - 1] = (b != 0.0) ? st_[sp - 1] / b : 0.0; break; }
        case Op::Mod: { double b = st_[--sp]; st_[sp - 1] = (b != 0.0) ? std::fmod(st_[sp - 1], b) : 0.0; break; }
        case Op::Neg: st_[sp - 1] = -st_[sp - 1]; break;
        case Op::Not: st_[sp - 1] = (st_[sp - 1] == 0.0) ? 1.0 : 0.0; break;
        case Op::CmpEq: { double b = st_[--sp]; st_[sp - 1] = (st_[sp - 1] == b) ? 1.0 : 0.0; break; }
        case Op::CmpNe: { double b = st_[--sp]; st_[sp - 1] = (st_[sp - 1] != b) ? 1.0 : 0.0; break; }
        case Op::CmpLt: { double b = st_[--sp]; st_[sp - 1] = (st_[sp - 1] < b) ? 1.0 : 0.0; break; }
        case Op::CmpLe: { double b = st_[--sp]; st_[sp - 1] = (st_[sp - 1] <= b) ? 1.0 : 0.0; break; }
        case Op::CmpGt: { double b = st_[--sp]; st_[sp - 1] = (st_[sp - 1] > b) ? 1.0 : 0.0; break; }
        case Op::CmpGe: { double b = st_[--sp]; st_[sp - 1] = (st_[sp - 1] >= b) ? 1.0 : 0.0; break; }
        case Op::Jmp: pc = in.a; break;
        case Op::Jz: { const double v = st_[--sp]; if (v == 0.0) pc = in.a; break; }
        case Op::Jnz: { const double v = st_[--sp]; if (v != 0.0) pc = in.a; break; }
        case Op::Call: {
          const int argc = static_cast<int>(in.b);
          double args[8] = {0, 0, 0, 0, 0, 0, 0, 0};
          const int start = sp - argc;
          for (int i = 0; i < argc && i < 8; ++i) args[i] = st_[start + i];
          sp = start;
          st_[sp++] = (in.a >= 23) ? BuiltinDsp(in.a, args, argc) : Builtin(in.a, args, argc);
          break;
        }
        case Op::Pop: --sp; break;
        case Op::CallUser: {
          const int argc = static_cast<int>(in.b);
          const int fid = in.a;
          if (fid < 0 || fid >= static_cast<int>(prog_->funcs.size())) break;
          if (call_depth_ >= kMaxCallDepth) return;  // 调用过深：中止
          const Func& f = prog_->funcs[static_cast<std::size_t>(fid)];
          if (frame_top_ + f.local_count > kMaxLocals) return;  // 局部栈溢出：中止
          double args[8] = {0, 0, 0, 0, 0, 0, 0, 0};
          const int start = sp - argc;
          for (int i = 0; i < argc && i < 8; ++i) args[i] = st_[start + i];
          sp = start;
          const int nb = frame_top_;
          for (int i = 0; i < argc && i < 8; ++i) {
            locals_[static_cast<std::size_t>(nb + i)] = static_cast<float>(args[i]);
          }
          for (int i = argc; i < f.local_count; ++i) {
            locals_[static_cast<std::size_t>(nb + i)] = 0.0f;
          }
          ret_pc_[call_depth_] = pc;
          ret_frame_[call_depth_] = frame_base_;
          ++call_depth_;
          frame_base_ = nb;
          frame_top_ = nb + f.local_count;
          pc = f.code_offset;
          break;
        }
        case Op::Ret: {
          const double rv = (sp > 0) ? st_[--sp] : 0.0;
          if (call_depth_ == 0) return;  // 顶层（sample/init）结束
          --call_depth_;
          frame_top_ = frame_base_;
          frame_base_ = ret_frame_[call_depth_];
          pc = ret_pc_[call_depth_];
          st_[sp++] = rv;
          break;
        }
      }
    }
  }
};

}  // namespace sonicraft::dsp::cvm
