# utils/code_generator.py

import os

from django.conf import settings
from jinja2 import Environment, FileSystemLoader


def generate_code_from_template(template_name, context, output_dir):
    """
    使用 Jinja2 模板引擎生成代码文件。

    Args:
        template_name (str): 模板文件名，不包含扩展名，如 'python_model'
        context (dict): 传递给模板的上下文数据
        output_dir (str): 输出文件的目录路径

    Returns:
        str: 生成的文件路径，或 None 如果失败
    """
    # 1. 定义模板目录路径
    templates_dir = os.path.join(settings.BASE_DIR, 'templates', 'code_templates')

    # 2. 创建 Jinja2 环境和加载器
    env = Environment(loader=FileSystemLoader(templates_dir))

    # 3. 加载模板
    try:
        template = env.get_template(f"{template_name}.j2")
    except Exception as e:
        print(f"Error loading template {template_name}.j2: {e}")
        return None

    # 4. 渲染模板
    try:
        rendered_code = template.render(context)
    except Exception as e:
        print(f"Error rendering template {template_name}.j2: {e}")
        return None

    # 5. 构造输出文件路径
    # 示例：根据模板名称决定文件扩展名
    file_extension = {
        'api': '.js',
        'python_model': '.py',
        'python_serializer': '.py',
        'python_viewset': '.py',
        'vue_component': '.vue'
    }.get(template_name, '.txt')  # 默认扩展名

    output_file_path = os.path.join(output_dir, f"{context.get('table_name', 'generated')}_{template_name}{file_extension}")

    # 6. 写入文件
    try:
        with open(output_file_path, 'w', encoding='utf-8') as f:
            f.write(rendered_code)
        print(f"Code generated successfully: {output_file_path}")
        return output_file_path
    except Exception as e:
        print(f"Error writing file {output_file_path}: {e}")
        return None