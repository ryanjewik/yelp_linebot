import lineLogo from './assets/line_qr.png'
import './App.css'
import DemoChat from './components/DemoChat'

function App() {
  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4 font-sans" style = {{ backgroundColor: '#f8fcdeff',  borderRadius: '12px', border: '3px solid #06c755' }}>
      {/* Main Container - LINE style card */}
      <div className="max-w-2xl w-full bg-white rounded-3xl shadow-xl ">
        {/* Header */}
        <div className="bg-gradient-to-r from-[#06c755] to-[#05b34a] p-8 text-center" style = {{color: '#2f2d2dff'}}>
          <h1 className="text-4xl font-bold text-white mb-2 tracking-tight" style = {{padding: '10px', margin: '1rem'}}>Yelp LINE Bot</h1>
          <p className="text-white/90 text-lg font-light" style = {{padding: '10px', margin: '1rem'}}>Your AI-powered restaurant assistant</p>
        </div>

        {/* Content */}
        <div className="p-10 space-y-8 ">
          {/* Welcome Message - Chat Bubble Style */}
          <div className="bg-[#06c755]/10 border-l-4 border-[#06c755] rounded-2xl p-6 mx-auto max-w-2xl" style = {{margin: '24px', padding: '10px'}}>
            <p className="text-gray-800 leading-relaxed text-center text-lg">
              Hi! 👋 I'm your Yelp assistant on LINE. I can help you discover amazing restaurants 
              based on your preferences, dietary restrictions, and budget. Just chat with me naturally!
            </p>
          </div>

          {/* Features Section */}
          <div className="space-y-4 flex flex-col items-center bg-[#06c755]/5" style = {{margin: '24px', paddingBottom: '24px', borderTop: '2px solid #06c755'}} >
            <h2 className="text-2xl font-semibold text-gray-800 mb-6 text-center">What I can do:</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-50 max-w-3xl" >
              {[
                { icon: '🍽️', text: 'Find restaurants based on your preferences' },
                { icon: '💬', text: 'Remember past conversations and recommendations' },
                { icon: '🎯', text: 'Personalize suggestions for your group' },
                { icon: '💰', text: 'Filter by price range and dietary needs' },
              ].map((feature, idx) => (
                <div key={idx} className="flex items-center gap-6 p-5 bg-gray-50 rounded-2xl hover:bg-[#06c755]/5 hover:border-[#06c755]/20 border border-transparent transition-all">
                  <span className="text-3xl">{feature.icon}</span>
                  <span className="text-gray-700 text-base">{feature.text}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Getting Started + QR Code Side by Side */}
          <div className="grid md:grid-cols-2 gap-6 items-start max-w-4xl mx-auto" style ={{ margin: '24px', borderTop: '2px solid #06c755', paddingBottom: '10px'}}>
            {/* Instructions */}
            <div className="bg-[#06c755]/5 p-8">
              <h3 className="font-semibold text-gray-800 mb-4 flex items-center justify-center gap-2 text-lg">
                <span className="text-2xl">📱</span>
                Getting Started
              </h3>
              <div className="flex flex-row items-center justify-center" style={{ gap: '32px' }}>
                <ol className="text-gray-700 font-light text-left flex-shrink-0" style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  <li className="flex gap-3 items-start">
                    <span className="font-semibold text-[#06c755] min-w-[24px]">1.</span>
                    <span>Scan the QR code with your LINE app</span>
                  </li>
                  <li className="flex gap-3 items-start">
                    <span className="font-semibold text-[#06c755] min-w-[24px]">2.</span>
                    <span>Add me as a friend</span>
                  </li>
                  <li className="flex gap-3 items-start">
                    <span className="font-semibold text-[#06c755] min-w-[24px]">3.</span>
                    <span>Start chatting! Try: <code className="bg-gray-200 px-2 py-0.5 rounded-md text-sm block mt-1">/yelp good vegan sushi in SF</code></span>
                  </li>
                </ol>
                <div className="bg-white p-2 rounded-2xl shadow-md border-2 border-[#06c755] hover:border-[#05b34a] transition-colors flex-shrink-0">
                  <img
                    src={lineLogo}
                    alt="LINE QR Code"
                    style={{ width: '15rem', height: '15rem'}}
                    className="object-contain" 
                  />
                </div>
              </div>
            </div>
          </div>
          {/* Commands List */}
          <div className="border-t border-[#06c755]/20 flex flex-col items-center justify-center bg-[#06c755]/5" style ={{borderTop: '2px solid #06c755', paddingBottom: '10px', margin: '24px'}}>
            <h4 className="font-semibold text-gray-800 text-sm text-center" style={{ marginBottom: '16px' }}>Available Commands:</h4>
            <div className="text-xs text-gray-600 font-light max-w-xs mx-auto" style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/help </code>
                <span> Show all commands</span>
              </div>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/diet </code>
                <span> Set dietary restrictions</span>
              </div>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/allergies </code>
                <span> Set allergens</span>
              </div>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/price </code>
                <span> Set price level 1-4</span>
              </div>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/favorites </code>
                <span> Set favorite cuisines</span>
              </div>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/prefs </code>
                <span> View preferences</span>
              </div>
              <div className="flex items-start" style={{ gap: '16px' }}>
                <code className="bg-gray-200 px-2 py-1 rounded-md whitespace-nowrap">/yelp </code>
                <span> Ask Yelp AI</span>
              </div>
            </div>
          </div>
          {/* DEMO area */}
          <div className="border-t border-[#06c755]/20 flex flex-col items-center justify-center bg-[#06c755]/5" style ={{borderTop: '2px solid #06c755', paddingBottom: '24px', paddingTop: '24px', margin: '24px'}}> 
            <h4 className="font-semibold text-gray-800 text-lg text-center" style={{ marginBottom: '16px' }}>
              Try out the Yelp API!
            </h4>
            <div style = {{ display: 'flex', flexDirection: 'column', width: '100%', maxWidth: '900px', height: '500px', backgroundColor: '#252525ff', borderRadius: '12px', border: '2px solid #06c755', overflow: 'hidden' }} className="demo-area">
              <DemoChat />
            </div>
          </div>
          {/* links footer */}
          <div className="border-t border-[#06c755]/20 flex flex-col items-center justify-center bg-[#06c755]/5" style ={{borderTop: '2px solid #06c755', paddingBottom: '16px', margin: '24px'}}> 
            <h4 className="font-light text-gray-600 text-xs text-center" style={{ marginBottom: '12px' }}>
              Made with ❤️ by Ryan Jewik. Powered by LINE Messaging API & Yelp Fusion API.
            </h4>
            <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
              <a 
                href="https://ryanhideo.dev" 
                target="_blank"
                rel="noopener noreferrer"
                className="text-[#06c755] hover:text-[#05b34a] text-sm font-medium transition-colors"
                style={{ textDecoration: 'none' }}
              >
                🌐 Portfolio
              </a>
              <span className="text-gray-400">|</span>
              <a 
                href="https://github.com/ryanjewik" 
                target="_blank"
                rel="noopener noreferrer"
                className="text-[#06c755] hover:text-[#05b34a] text-sm font-medium transition-colors"
                style={{ textDecoration: 'none' }}
              >
                💻 GitHub
              </a>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default App
