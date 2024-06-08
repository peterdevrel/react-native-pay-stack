require 'json'

package = JSON.parse(File.read(File.join(__dir__, './package.json')))

Pod::Spec.new do |s|
  s.name           = '@peterdevrel/react-native-pay-stack'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = 'https://github.com/peterdevrel/react-native-pay-stack'
  s.source         = { :git => 'https://github.com/peterdevrel/react-native-pay-stack.git', :tag => "v#{s.version}" }

  s.platform       = :ios, '9.0'

  s.preserve_paths = 'README.md', 'package.json', 'index.js'
  s.source_files   = 'ios/*.{h,m}'

  s.compiler_flags = '-fno-modules'

  s.dependency 'React'
  s.dependency 'Paystack'
end