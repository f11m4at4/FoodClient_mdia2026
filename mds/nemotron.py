import argparse
import os
import sys

from openai import OpenAI


DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
DEFAULT_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"


def configure_stdout() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Call NVIDIA Nemotron through the NVIDIA OpenAI-compatible API."
    )
    parser.add_argument(
        "prompt",
        nargs="*",
        help="Prompt text. If omitted, the script reads from stdin.",
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"Model name to call. Default: {DEFAULT_MODEL}",
    )
    parser.add_argument(
        "--api-key",
        default=os.environ.get("NVIDIA_API_KEY") or os.environ.get("NVAPI_KEY"),
        help="NVIDIA API key. Defaults to NVIDIA_API_KEY or NVAPI_KEY.",
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("NVIDIA_BASE_URL", DEFAULT_BASE_URL),
        help=f"API base URL. Default: {DEFAULT_BASE_URL}",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=1.0,
        help="Sampling temperature. Default: 1.0",
    )
    parser.add_argument(
        "--top-p",
        type=float,
        default=0.95,
        help="Top-p sampling value. Default: 0.95",
    )
    parser.add_argument(
        "--max-tokens",
        type=int,
        default=16384,
        help="Maximum output tokens. Default: 16384",
    )
    parser.add_argument(
        "--reasoning-budget",
        type=int,
        default=16384,
        help="Reasoning budget sent in extra_body. Default: 16384",
    )
    parser.add_argument(
        "--show-reasoning",
        action="store_true",
        help="Print reasoning_content chunks when the model returns them.",
    )
    return parser


def resolve_prompt(prompt_parts: list[str]) -> str:
    if prompt_parts:
        return " ".join(prompt_parts).strip()

    if not sys.stdin.isatty():
        return sys.stdin.read().strip()

    return ""


def main() -> int:
    configure_stdout()
    parser = build_parser()
    args = parser.parse_args()
    prompt = resolve_prompt(args.prompt)

    if not args.api_key:
        parser.error("Missing API key. Set NVIDIA_API_KEY or pass --api-key.")

    if not prompt:
        parser.error("Missing prompt. Pass text as arguments or pipe stdin.")

    client = OpenAI(
        base_url=args.base_url,
        api_key=args.api_key,
    )

    completion = client.chat.completions.create(
        model=args.model,
        messages=[{"role": "user", "content": prompt}],
        temperature=args.temperature,
        top_p=args.top_p,
        max_tokens=args.max_tokens,
        extra_body={
            "chat_template_kwargs": {"enable_thinking": True},
            "reasoning_budget": args.reasoning_budget,
        },
        stream=True,
    )

    for chunk in completion:
        if not chunk.choices:
            continue
        delta = chunk.choices[0].delta
        reasoning = getattr(delta, "reasoning_content", None)
        if args.show_reasoning and reasoning:
            print(reasoning, end="", flush=True)
        if delta.content is not None:
            print(delta.content, end="", flush=True)

    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
